package app.lurk.data

import android.net.Uri
import android.text.TextUtils
import app.lurk.web.BlueskyLinks
import app.lurk.web.Links
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/**
 * Turns Bluesky's app.bsky JSON into the shared models. A post's (and reply's) name and
 * thread ref are its at:// URI. Posts have no titles: the text is the post. Feeds become
 * [CommunityInfo]s named by their URI, with their display names handed to [naming].
 * [onViewer] hears each post's cid and the URI of the account's like on it, which
 * likes and bookmarks need later.
 */
class BlueskyParse(
    private val naming: BlueskyNaming,
    private val onViewer: (uri: String, cid: String, likeUri: String?) -> Unit,
) {

    /** One entry of a timeline or feed: the post, noting a repost or what it replies to. */
    fun feedItem(item: JSONObject): Post? {
        val pv = item.optJSONObject("post") ?: return null
        val reason = item.optJSONObject("reason")
        val reasonType = reason?.optString("\$type").orEmpty()
        val note = when {
            reasonType.endsWith("#reasonRepost") -> reason?.optJSONObject("by")?.str("handle")?.let { "Reposted by @$it" }
            else -> item.optJSONObject("reply")?.optJSONObject("parent")?.optJSONObject("author")?.str("handle")
                ?.let { "Reply to @$it" }
        }
        return post(pv, flair = note, pinned = reasonType.endsWith("#reasonPin"))
    }

    fun post(pv: JSONObject, flair: String? = null, pinned: Boolean = false): Post {
        val uri = pv.getString("uri")
        noteViewer(pv)
        val record = pv.optJSONObject("record") ?: JSONObject()
        val author = handle(pv.optJSONObject("author"))
        val text = record.optString("text")
        val embed = Embed.of(pv.optJSONObject("embed"))
        val quote = embed.quote?.let(::quoted)
        val html = buildString {
            append(richText(record))
            quote?.let { append("<blockquote>").append(it.html).append("</blockquote>") }
        }
        val plain = buildString {
            append(text)
            quote?.let { if (isNotEmpty()) append("\n\n"); append("Quoting ${it.plain}") }
        }
        val media = embed.media() ?: embed.quoteEmbed?.media()
        val link = embed.external?.takeIf { media == null }
        val share = BlueskyLinks.webPost(uri)
        return Post(
            name = uri,
            id = uri.substringAfterLast('/'),
            title = "",
            author = author,
            community = null,
            score = pv.optInt("likeCount"),
            likes = if (pv.optJSONObject("viewer")?.str("like") != null) true else null,
            saved = pv.optJSONObject("viewer")?.optBoolean("bookmarked") == true,
            numComments = pv.optInt("replyCount"),
            createdUtc = LemmyParse.time(record.str("createdAt") ?: pv.str("indexedAt")),
            threadRef = uri,
            url = link?.str("uri") ?: share,
            domain = link?.str("uri")?.let { Uri.parse(it).host?.removePrefix("www.") }.orEmpty(),
            isSelf = link == null,
            selftext = plain,
            selftextHtml = html.takeIf { plain.isNotBlank() },
            flair = flair,
            over18 = adult(pv),
            spoiler = false,
            stickied = pinned,
            locked = false,
            media = media,
            thumbnail = link?.str("thumb"),
            shareUrl = share,
        )
    }

    /**
     * The replies under a thread node, depth first, ordered by [sort] ("top", "newest",
     * "oldest"). A reply whose own replies weren't sent (past the depth limit) gets a
     * [More] row that loads them in place.
     */
    fun replies(node: JSONObject, parentName: String, depth: Int, op: String, sort: String): List<ThreadItem> {
        val out = mutableListOf<ThreadItem>()
        fun place(n: JSONObject, parent: String, d: Int) {
            val children = n.optJSONArray("replies") ?: return
            val posts = (0 until children.length()).mapNotNull { children.optJSONObject(it) }
                .filter { it.optString("\$type").endsWith("#threadViewPost") && it.has("post") }
            val sorted = when (sort) {
                "newest" -> posts.sortedByDescending { created(it) }
                "oldest" -> posts.sortedBy { created(it) }
                // The author's own replies first, as on Bluesky, then by likes.
                else -> posts.sortedWith(
                    compareByDescending<JSONObject> { handle(it.getJSONObject("post").optJSONObject("author")) == op }
                        .thenByDescending { it.getJSONObject("post").optInt("likeCount") },
                )
            }
            for (child in sorted) {
                val c = comment(child.getJSONObject("post"), d, parent, op)
                out += c
                val count = child.getJSONObject("post").optInt("replyCount")
                if (child.has("replies")) {
                    place(child, c.name, d + 1)
                } else if (count > 0) {
                    out += More(name = "more:${c.name}", children = listOf(c.name), count = count, depth = d + 1, parentName = c.name)
                }
            }
        }
        place(node, parentName, depth)
        return out
    }

    private fun created(n: JSONObject): Long {
        val p = n.getJSONObject("post")
        return LemmyParse.time(p.optJSONObject("record")?.str("createdAt") ?: p.str("indexedAt"))
    }

    fun comment(pv: JSONObject, depth: Int, parentName: String, op: String): Comment {
        val uri = pv.getString("uri")
        noteViewer(pv)
        val record = pv.optJSONObject("record") ?: JSONObject()
        val author = handle(pv.optJSONObject("author"))
        val embed = Embed.of(pv.optJSONObject("embed"))
        val html = buildString {
            append(richText(record))
            embed.external?.let { ext ->
                val href = ext.str("uri") ?: return@let
                append("<br><a href=\"").append(attr(href)).append("\">")
                    .append(esc(ext.str("title") ?: Uri.parse(href).host.orEmpty())).append("</a>")
            }
            if (embed.video != null) {
                append("<br><a href=\"").append(attr(BlueskyLinks.webPost(uri))).append("\">▶ Video</a>")
            }
            embed.quote?.let(::quoted)?.let { append("<blockquote>").append(it.html).append("</blockquote>") }
        }
        val images = embed.images.map { img ->
            val full = img.str("fullsize") ?: img.optString("thumb")
            val ratio = img.optJSONObject("aspectRatio")
            InlineImage(full, img.str("thumb") ?: full, full, ratio?.optInt("width") ?: 0, ratio?.optInt("height") ?: 0)
        }
        val viewer = pv.optJSONObject("viewer")
        return Comment(
            name = uri,
            author = author,
            bodyHtml = html,
            score = pv.optInt("likeCount"),
            scoreHidden = false,
            likes = if (viewer?.str("like") != null) true else null,
            saved = viewer?.optBoolean("bookmarked") == true,
            createdUtc = LemmyParse.time(record.str("createdAt") ?: pv.str("indexedAt")),
            depth = depth,
            parentName = parentName,
            isOp = author == op,
            stickied = false,
            distinguished = null,
            flair = null,
            images = images,
        )
    }

    /** A feed generator, as a Discover card. Its likes stand in for members. */
    fun generator(gv: JSONObject): CommunityInfo {
        val uri = gv.getString("uri")
        naming.remember(uri, gv.str("displayName"))
        return CommunityInfo(
            name = uri,
            title = gv.optJSONObject("creator")?.str("handle")?.let { "by @$it" },
            description = gv.str("description")?.replace(Regex("\\s+"), " ")?.trim().orEmpty(),
            subscribers = gv.optInt("likeCount"),
            activeUsers = null,
            icon = gv.str("avatar"),
            banner = null,
            over18 = adult(gv),
            subscribed = false,
        )
    }

    /** Why a thread node has no post. */
    fun missing(node: JSONObject): String = when {
        node.optString("\$type").endsWith("#blockedPost") -> "You can't see this post because of a block."
        else -> "That post was deleted."
    }

    private fun noteViewer(pv: JSONObject) {
        val uri = pv.str("uri") ?: return
        val cid = pv.str("cid") ?: return
        onViewer(uri, cid, pv.optJSONObject("viewer")?.str("like"))
    }

    private fun handle(author: JSONObject?): String = author?.str("handle") ?: "[deleted]"

    private class Quote(val html: String, val plain: String)

    /** A quoted post, as a line of text: who, and what they said. */
    private fun quoted(view: JSONObject): Quote? {
        val type = view.optString("\$type")
        if (!type.endsWith("#viewRecord")) {
            val reason = when {
                type.endsWith("#viewNotFound") -> "a deleted post"
                type.endsWith("#viewBlocked") -> "a blocked post"
                type.endsWith("#viewDetached") -> "a post its author detached"
                else -> return null // a feed, list or starter pack
            }
            return Quote("<i>Quoting $reason</i>", reason)
        }
        val who = handle(view.optJSONObject("author"))
        val value = view.optJSONObject("value") ?: JSONObject()
        val href = view.str("uri")?.let(BlueskyLinks::webPost)
        val name = if (href != null) "<a href=\"${attr(href)}\"><b>@${esc(who)}</b></a>" else "<b>@${esc(who)}</b>"
        return Quote("$name<br>${richText(value)}", "@$who: ${value.optString("text")}")
    }

    /** What a post shows besides its text. Views can nest a quote with media of its own. */
    private class Embed(
        val images: List<JSONObject> = emptyList(),
        val video: JSONObject? = null,
        val external: JSONObject? = null,
        /** The quoted record's view (#viewRecord, or why it can't be shown). */
        val quote: JSONObject? = null,
    ) {
        /** The quoted post's own media, for a quote of a picture with no media of its own. */
        val quoteEmbed: Embed?
            get() = quote?.optJSONArray("embeds")?.optJSONObject(0)?.let(::of)

        fun media(): PostMedia? {
            if (images.isNotEmpty()) {
                val pictures = images.map { img ->
                    val ratio = img.optJSONObject("aspectRatio")
                    val full = img.str("fullsize") ?: img.optString("thumb")
                    Picture(img.str("thumb") ?: full, full, ratio?.optInt("width") ?: 0, ratio?.optInt("height") ?: 0)
                }
                return if (pictures.size == 1) PostMedia.Image(pictures[0]) else PostMedia.Gallery(pictures)
            }
            video?.let { v ->
                val ratio = v.optJSONObject("aspectRatio")
                val w = ratio?.optInt("width") ?: 0
                val h = ratio?.optInt("height") ?: 0
                val preview = v.str("thumbnail")?.let { Picture(it, it, w, h) }
                val stream = v.str("playlist")?.let { VideoStream(it, null, w, h, 0, isGif = false) }
                return PostMedia.Video(preview, stream)
            }
            val ext = external ?: return null
            val link = Uri.parse(ext.str("uri") ?: return null)
            // Tenor GIFs and other direct files play or show in place; video sites open their player.
            if (Links.isImageFile(link)) return PostMedia.Image(Picture(ext.str("thumb") ?: link.toString(), link.toString(), 0, 0))
            Links.directVideo(link)?.let { (file, gifLike) ->
                return PostMedia.Video(ext.str("thumb")?.let { Picture(it, it, 0, 0) }, VideoStream(null, file, 0, 0, 0, gifLike))
            }
            if (Links.isVideoPage(link)) return PostMedia.Video(ext.str("thumb")?.let { Picture(it, it, 0, 0) }, null)
            return null
        }

        companion object {
            fun of(view: JSONObject?): Embed {
                if (view == null) return Embed()
                val type = view.optString("\$type")
                return when {
                    type.startsWith("app.bsky.embed.images") -> Embed(images = view.optJSONArray("images").objects())
                    type.startsWith("app.bsky.embed.video") -> Embed(video = view)
                    type.startsWith("app.bsky.embed.external") -> Embed(external = view.optJSONObject("external"))
                    type.startsWith("app.bsky.embed.recordWithMedia") -> {
                        val media = of(view.optJSONObject("media"))
                        Embed(media.images, media.video, media.external, view.optJSONObject("record")?.optJSONObject("record"))
                    }
                    type.startsWith("app.bsky.embed.record") -> Embed(quote = view.optJSONObject("record"))
                    else -> Embed()
                }
            }

            private fun JSONArray?.objects(): List<JSONObject> =
                if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
        }
    }

    companion object {
        private val adultLabels = setOf("porn", "sexual", "nudity", "graphic-media", "gore")

        /** Labeled adult by a moderation service or by its author. */
        fun adult(view: JSONObject): Boolean {
            val labels = view.optJSONArray("labels") ?: return false
            return (0 until labels.length()).any { labels.optJSONObject(it)?.optString("val") in adultLabels }
        }

        /**
         * A post record's text as HTML, its facets made into links: URLs, @mentions (to the
         * profile) and #tags. Facet offsets count UTF-8 bytes, not characters.
         */
        fun richText(record: JSONObject): String {
            val text = record.optString("text")
            if (text.isEmpty()) return ""
            val bytes = text.toByteArray(Charsets.UTF_8)
            val facets = record.optJSONArray("facets")
            class Span(val start: Int, val end: Int, val href: String)
            val spans = mutableListOf<Span>()
            if (facets != null) {
                for (i in 0 until facets.length()) {
                    val f = facets.optJSONObject(i) ?: continue
                    val index = f.optJSONObject("index") ?: continue
                    val start = index.optInt("byteStart", -1)
                    val end = index.optInt("byteEnd", -1)
                    if (start < 0 || end <= start || end > bytes.size) continue
                    val feature = f.optJSONArray("features")?.optJSONObject(0) ?: continue
                    val type = feature.optString("\$type")
                    val href = when {
                        type.endsWith("#link") -> feature.str("uri")
                        type.endsWith("#mention") -> feature.str("did")?.let { "https://bsky.app/profile/$it" }
                        type.endsWith("#tag") -> feature.str("tag")?.let { "https://bsky.app/hashtag/" + Uri.encode(it) }
                        else -> null
                    } ?: continue
                    spans += Span(start, end, href)
                }
            }
            val out = StringBuilder()
            var at = 0
            fun piece(from: Int, to: Int) = esc(String(bytes, from, to - from, Charsets.UTF_8))
            for (s in spans.sortedBy { it.start }) {
                if (s.start < at) continue // overlapping facets: keep the first
                out.append(piece(at, s.start))
                out.append("<a href=\"").append(attr(s.href)).append("\">").append(piece(s.start, s.end)).append("</a>")
                at = s.end
            }
            out.append(piece(at, bytes.size))
            return out.toString().replace("\n", "<br>")
        }

        private fun esc(s: String): String = TextUtils.htmlEncode(s)

        private fun attr(s: String): String = s.replace("&", "&amp;").replace("\"", "&quot;")
    }
}
