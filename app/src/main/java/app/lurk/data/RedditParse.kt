package app.lurk.data

import org.json.JSONArray
import org.json.JSONObject

/** Turns Reddit's JSON (requested with raw_json=1, so nothing is HTML-escaped) into models. */
object RedditParse {

    fun feed(root: JSONObject): FeedPage {
        val data = root.getJSONObject("data")
        val children = data.optJSONArray("children") ?: JSONArray()
        val posts = (0 until children.length()).mapNotNull { i ->
            val child = children.getJSONObject(i)
            if (child.optString("kind") == "t3") post(child.getJSONObject("data")) else null
        }
        return FeedPage(posts, data.str("after"))
    }

    /** [requestedPath] tells whether this is a single comment's thread (/r/x/comments/id/slug/cid). */
    fun thread(root: JSONArray, requestedPath: String): ThreadData {
        val post = post(root.getJSONObject(0).getJSONObject("data").getJSONArray("children")
            .getJSONObject(0).getJSONObject("data"))
        val items = mutableListOf<ThreadItem>()
        val comments = root.optJSONObject(1)?.optJSONObject("data")?.optJSONArray("children")
        if (comments != null) addTree(comments, 0, post.author, items)
        val segments = requestedPath.trim('/').split('/')
        val afterComments = segments.size - segments.indexOf("comments") - 1
        return ThreadData(post, items, isSingleThread = "comments" in segments && afterComments >= 3)
    }

    /**
     * For the debug panel: how a thread's comment images arrived. Describes the first comment
     * that looks like it has one, or null if none do.
     */
    fun imageDiagnostics(root: JSONArray): String? {
        var total = 0
        var withMeta = 0
        var sample: JSONObject? = null
        fun walk(children: JSONArray?) {
            if (children == null) return
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                if (child.optString("kind") != "t1") continue
                val d = child.optJSONObject("data") ?: continue
                total++
                val hasMeta = d.optJSONObject("media_metadata") != null
                if (hasMeta) withMeta++
                val body = d.optString("body")
                val looksLikeImage = hasMeta || listOf("preview.redd.it", "i.redd.it", "giphy", "![").any { it in body }
                if (sample == null && looksLikeImage) sample = d
                walk(d.optJSONObject("replies")?.optJSONObject("data")?.optJSONArray("children"))
            }
        }
        walk(root.optJSONObject(1)?.optJSONObject("data")?.optJSONArray("children"))
        val d = sample ?: return null
        val meta = d.optJSONObject("media_metadata")
        val metaInfo = meta?.let { all ->
            all.keys().asSequence().joinToString { key ->
                val m = all.optJSONObject(key)
                "$key(${m?.optString("status")}/${m?.optString("e")}, s=${m?.optJSONObject("s")?.keys()?.asSequence()?.toList()})"
            }
        } ?: "none"
        return "Comment images: $withMeta of $total comments have media_metadata. Sample ${d.optString("name")}: " +
            "body=${d.optString("body").take(160)} | body_html=${d.optString("body_html").take(300)} | meta=$metaInfo"
    }

    /** Things from /api/morechildren, arranged under [more]'s parent. */
    fun moreChildren(root: JSONObject, more: More, op: String): List<ThreadItem> {
        val things = root.optJSONObject("json")?.optJSONObject("data")?.optJSONArray("things") ?: JSONArray()
        val flat = (0 until things.length()).mapNotNull { i -> thing(things.getJSONObject(i), 0, op) }
        val byParent = flat.groupBy { it.parentName }
        val out = mutableListOf<ThreadItem>()
        val placed = HashSet<String>()

        fun place(parent: String, depth: Int) {
            byParent[parent]?.forEach { item ->
                if (!placed.add(item.key)) return@forEach
                out += item.atDepth(depth)
                if (item is Comment) place(item.name, depth + 1)
            }
        }
        place(more.parentName, more.depth)
        flat.filter { it.key !in placed }.forEach { out += it.atDepth(more.depth) }
        return out
    }

    private fun ThreadItem.atDepth(depth: Int): ThreadItem = when (this) {
        is Comment -> copy(depth = depth)
        is More -> copy(depth = depth)
    }

    private fun addTree(children: JSONArray, depth: Int, op: String, out: MutableList<ThreadItem>) {
        for (i in 0 until children.length()) {
            val child = children.getJSONObject(i)
            val item = thing(child, depth, op) ?: continue
            out += item
            if (item is Comment) {
                val replies = child.getJSONObject("data").optJSONObject("replies")
                    ?.optJSONObject("data")?.optJSONArray("children")
                if (replies != null) addTree(replies, depth + 1, op, out)
            }
        }
    }

    private fun thing(child: JSONObject, depth: Int, op: String): ThreadItem? {
        val d = child.optJSONObject("data") ?: return null
        return when (child.optString("kind")) {
            "t1" -> Comment(
                name = d.getString("name"),
                author = d.optString("author", "[deleted]"),
                bodyHtml = d.optString("body_html"),
                score = d.optInt("score"),
                scoreHidden = d.optBoolean("score_hidden"),
                likes = d.likes(),
                saved = d.optBoolean("saved"),
                createdUtc = d.optDouble("created_utc", 0.0).toLong(),
                depth = depth,
                parentName = d.optString("parent_id"),
                isOp = d.optBoolean("is_submitter") || d.optString("author") == op,
                stickied = d.optBoolean("stickied"),
                distinguished = d.str("distinguished"),
                flair = d.str("author_flair_text"),
                images = commentImages(d),
            )
            "more" -> {
                val ids = d.optJSONArray("children") ?: JSONArray()
                More(
                    name = d.optString("name"),
                    children = (0 until ids.length()).map { ids.getString(it) },
                    count = d.optInt("count"),
                    depth = depth,
                    parentName = d.optString("parent_id"),
                )
            }
            else -> null
        }
    }

    /** A subreddit listing (/subreddits/popular.json), minus user profiles and private ones. */
    fun communities(root: JSONObject): CommunityPage {
        val data = root.getJSONObject("data")
        val children = data.optJSONArray("children") ?: JSONArray()
        val list = (0 until children.length()).mapNotNull { i ->
            val child = children.getJSONObject(i)
            if (child.optString("kind") != "t5") return@mapNotNull null
            val d = child.getJSONObject("data")
            val name = d.str("display_name") ?: return@mapNotNull null
            val type = d.optString("subreddit_type")
            if (name.startsWith("u_") || type == "user" || type == "private" || d.optBoolean("quarantine")) {
                return@mapNotNull null
            }
            CommunityInfo(
                name = name,
                title = d.str("title")?.takeIf { !it.equals(name, ignoreCase = true) },
                description = d.str("public_description")?.trim().orEmpty(),
                subscribers = d.optInt("subscribers", -1).takeIf { it > 0 },
                activeUsers = d.optInt("active_user_count", -1).takeIf { it >= 0 }
                    ?: d.optInt("accounts_active", -1).takeIf { it >= 0 },
                icon = d.str("community_icon") ?: d.str("icon_img"),
                banner = d.str("banner_background_image") ?: d.str("mobile_banner_image") ?: d.str("banner_img"),
                over18 = d.optBoolean("over18"),
                subscribed = d.optBoolean("user_is_subscriber"),
            )
        }
        return CommunityPage(list, data.str("after"))
    }

    fun post(d: JSONObject): Post {
        // Crossposts keep their media on the original post.
        val source = d.optJSONArray("crosspost_parent_list")?.optJSONObject(0) ?: d
        val media = media(source)
        val isSelf = d.optBoolean("is_self")
        return Post(
            name = d.getString("name"),
            id = d.getString("id"),
            title = d.optString("title"),
            author = d.optString("author", "[deleted]"),
            community = d.optString("subreddit"),
            score = d.optInt("score"),
            likes = d.likes(),
            saved = d.optBoolean("saved"),
            numComments = d.optInt("num_comments"),
            createdUtc = d.optDouble("created_utc", 0.0).toLong(),
            threadRef = d.optString("permalink"),
            url = d.optString("url"),
            domain = d.optString("domain"),
            isSelf = isSelf,
            selftext = d.optString("selftext"),
            selftextHtml = d.str("selftext_html"),
            flair = d.str("link_flair_text"),
            over18 = d.optBoolean("over_18"),
            spoiler = d.optBoolean("spoiler"),
            stickied = d.optBoolean("stickied"),
            locked = d.optBoolean("locked"),
            media = media,
            thumbnail = if (media == null && !isSelf) linkThumbnail(source) else null,
        )
    }

    private fun media(d: JSONObject): PostMedia? {
        if (d.optBoolean("is_gallery")) {
            val meta = d.optJSONObject("media_metadata")
            val items = d.optJSONObject("gallery_data")?.optJSONArray("items")
            if (meta != null && items != null) {
                val pictures = (0 until items.length()).mapNotNull { i ->
                    meta.optJSONObject(items.getJSONObject(i).optString("media_id"))?.let(::galleryPicture)
                }
                if (pictures.isNotEmpty()) return PostMedia.Gallery(pictures)
            }
        }
        val hint = d.optString("post_hint")
        // Reddit's own upload, or the MP4 Reddit makes of GIFs and Imgur .gifv links.
        val hosted = (d.optJSONObject("secure_media") ?: d.optJSONObject("media"))?.optJSONObject("reddit_video")
        val converted = d.optJSONObject("preview")?.optJSONObject("reddit_video_preview")
        if (d.optBoolean("is_video") || hint == "hosted:video") {
            return PostMedia.Video(previewPicture(d), (hosted ?: converted)?.let(::videoStream))
        }
        if (hint == "rich:video") {
            val preview = previewPicture(d) ?: oembedThumbnail(d)
            return PostMedia.Video(preview, converted?.let(::videoStream), embed(d))
        }
        if (converted != null && hint != "image") {
            return PostMedia.Video(previewPicture(d), videoStream(converted))
        }
        if (hint == "image") return previewPicture(d)?.let { PostMedia.Image(it) }
        return null
    }

    private fun embed(d: JSONObject): VideoEmbed? {
        val box = d.optJSONObject("secure_media_embed") ?: d.optJSONObject("media_embed") ?: return null
        val html = box.str("content")?.takeIf { "<iframe" in it } ?: return null
        val oembed = (d.optJSONObject("secure_media") ?: d.optJSONObject("media"))?.optJSONObject("oembed")
        return VideoEmbed(
            html = html,
            width = box.optInt("width").takeIf { it > 0 } ?: oembed?.optInt("width") ?: 0,
            height = box.optInt("height").takeIf { it > 0 } ?: oembed?.optInt("height") ?: 0,
            provider = oembed?.str("provider_name"),
        )
    }

    private fun oembedThumbnail(d: JSONObject): Picture? {
        val o = (d.optJSONObject("secure_media") ?: d.optJSONObject("media"))?.optJSONObject("oembed") ?: return null
        val url = o.str("thumbnail_url") ?: return null
        return Picture(url, url, o.optInt("thumbnail_width"), o.optInt("thumbnail_height"))
    }

    private fun videoStream(v: JSONObject): VideoStream? {
        val hls = v.str("hls_url")
        val mp4 = v.str("fallback_url")
        if (hls == null && mp4 == null) return null
        return VideoStream(
            hlsUrl = hls,
            mp4Url = mp4,
            width = v.optInt("width"),
            height = v.optInt("height"),
            durationSec = v.optInt("duration"),
            isGif = v.optBoolean("is_gif"),
            hasAudio = if (v.has("has_audio")) v.optBoolean("has_audio") else null,
        )
    }

    private fun previewPicture(d: JSONObject): Picture? {
        val image = d.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0) ?: return null
        val source = image.optJSONObject("source") ?: return null
        val full = source.optString("url").ifBlank { return null }
        // A mid-size rendition is plenty for lists and saves data; the viewer uses the original.
        val sizes = image.optJSONArray("resolutions") ?: JSONArray()
        val mid = (0 until sizes.length()).map { sizes.getJSONObject(it) }
            .firstOrNull { it.optInt("width") >= 640 }
        return Picture(
            url = mid?.optString("url")?.ifBlank { null } ?: full,
            fullUrl = full,
            width = source.optInt("width"),
            height = source.optInt("height"),
        )
    }

    private fun galleryPicture(meta: JSONObject): Picture? {
        if (meta.optString("status") != "valid") return null
        val s = meta.optJSONObject("s") ?: return null
        val full = s.str("u") ?: s.str("gif") ?: return null
        val sizes = meta.optJSONArray("p") ?: JSONArray()
        val mid = (0 until sizes.length()).map { sizes.getJSONObject(it) }
            .firstOrNull { it.optInt("x") >= 640 }
        return Picture(mid?.str("u") ?: full, full, s.optInt("x"), s.optInt("y"))
    }

    private fun commentImages(d: JSONObject): List<InlineImage> {
        val meta = d.optJSONObject("media_metadata") ?: return emptyList()
        return meta.keys().asSequence().mapNotNull { key ->
            val m = meta.optJSONObject(key) ?: return@mapNotNull null
            if (m.optString("status") != "valid") return@mapNotNull null
            val s = m.optJSONObject("s") ?: return@mapNotNull null
            when (m.optString("e")) {
                "AnimatedImage" -> s.str("gif")?.let { InlineImage(key, it, it, s.optInt("x"), s.optInt("y")) }
                "Image" -> galleryPicture(m)?.let { InlineImage(key, it.url, it.fullUrl, it.width, it.height) }
                else -> null
            }
        }.toList()
    }

    private fun linkThumbnail(d: JSONObject): String? =
        previewPicture(d)?.url ?: d.optString("thumbnail").takeIf { it.startsWith("http") }

    private fun JSONObject.likes(): Boolean? = if (isNull("likes")) null else optBoolean("likes")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
