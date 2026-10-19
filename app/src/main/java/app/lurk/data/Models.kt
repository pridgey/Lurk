package app.lurk.data

import org.json.JSONArray
import org.json.JSONObject

/** One image, at the size shown in lists ([url]) and full size for the viewer ([fullUrl]). */
data class Picture(val url: String, val fullUrl: String, val width: Int, val height: Int) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1.5f
}

/** The big media shown with a post. Plain links use [Post.thumbnail] instead. */
sealed interface PostMedia {
    data class Image(val picture: Picture) : PostMedia
    data class Gallery(val pictures: List<Picture>) : PostMedia
    data class Video(val preview: Picture?) : PostMedia
}

data class Post(
    /** Fullname, e.g. t3_abc123. */
    val name: String,
    val id: String,
    val title: String,
    val author: String,
    val subreddit: String,
    val score: Int,
    /** true = upvoted, false = downvoted, null = no vote. */
    val likes: Boolean?,
    val saved: Boolean,
    val numComments: Int,
    val createdUtc: Long,
    val permalink: String,
    val url: String,
    val domain: String,
    val isSelf: Boolean,
    val selftext: String,
    val selftextHtml: String?,
    val flair: String?,
    val over18: Boolean,
    val spoiler: Boolean,
    val stickied: Boolean,
    val locked: Boolean,
    val media: PostMedia?,
    val thumbnail: String?,
)

/** A row in a comment thread: a comment, or a "load more" placeholder. */
sealed interface ThreadItem {
    val key: String
    val depth: Int
    val parentName: String
}

data class Comment(
    val name: String,
    val author: String,
    val bodyHtml: String,
    val score: Int,
    val scoreHidden: Boolean,
    val likes: Boolean?,
    val saved: Boolean,
    val createdUtc: Long,
    override val depth: Int,
    override val parentName: String,
    val isOp: Boolean,
    val stickied: Boolean,
    /** "moderator", "admin" or null. */
    val distinguished: String?,
    val flair: String?,
) : ThreadItem {
    override val key: String get() = name
}

data class More(
    val name: String,
    /** Comment ids still to load. Empty means "continue this thread" (needs its own page). */
    val children: List<String>,
    val count: Int,
    override val depth: Int,
    override val parentName: String,
) : ThreadItem {
    // "Continue this thread" rows all share one name, so the parent keeps them apart.
    override val key: String get() = "more:$name:$parentName:${children.firstOrNull()}"
}

class FeedPage(val posts: List<Post>, val after: String?)

class ThreadData(val post: Post, val items: List<ThreadItem>, val isSingleThread: Boolean)

/** Turns Reddit's JSON (requested with raw_json=1, so nothing is HTML-escaped) into models. */
object Parse {

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
            subreddit = d.optString("subreddit"),
            score = d.optInt("score"),
            likes = d.likes(),
            saved = d.optBoolean("saved"),
            numComments = d.optInt("num_comments"),
            createdUtc = d.optDouble("created_utc", 0.0).toLong(),
            permalink = d.optString("permalink"),
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
        if (d.optBoolean("is_video") || hint == "hosted:video" || hint == "rich:video") {
            return PostMedia.Video(previewPicture(d))
        }
        if (hint == "image") return previewPicture(d)?.let { PostMedia.Image(it) }
        return null
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

    private fun linkThumbnail(d: JSONObject): String? =
        previewPicture(d)?.url ?: d.optString("thumbnail").takeIf { it.startsWith("http") }

    private fun JSONObject.likes(): Boolean? = if (isNull("likes")) null else optBoolean("likes")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
