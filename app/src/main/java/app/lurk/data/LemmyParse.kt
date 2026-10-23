package app.lurk.data

import android.net.Uri
import app.lurk.web.Links
import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns Lemmy's API v3 JSON into the shared models. Names: posts are "p<id>" and
 * comments "c<id>", so votes and saves know which endpoint to use. People and
 * communities from other instances are "name@host"; local ones are just "name".
 */
object LemmyParse {

    fun post(pv: JSONObject): Post {
        val p = pv.getJSONObject("post")
        val community = pv.optJSONObject("community")
        val counts = pv.optJSONObject("counts")
        val id = p.getLong("id").toString()
        val url = p.str("url")
        val body = p.str("body").orEmpty()
        val html = body.takeIf { it.isNotBlank() }?.let(Markdown::toHtml)
        val media = url?.let { media(p, pv, it) }
        return Post(
            name = "p$id",
            id = id,
            title = p.optString("name"),
            author = person(pv.optJSONObject("creator")),
            community = community?.let(::community),
            score = counts?.optInt("score") ?: 0,
            likes = myVote(pv),
            saved = pv.optBoolean("saved"),
            numComments = counts?.optInt("comments") ?: 0,
            createdUtc = time(p.str("published")),
            threadRef = "post:$id",
            url = url.orEmpty(),
            domain = url?.let { Uri.parse(it).host }.orEmpty(),
            isSelf = url == null,
            selftext = body,
            selftextHtml = html,
            flair = null,
            over18 = p.optBoolean("nsfw") || community?.optBoolean("nsfw") == true,
            spoiler = false,
            stickied = p.optBoolean("featured_community") || p.optBoolean("featured_local"),
            locked = p.optBoolean("locked"),
            media = media,
            thumbnail = if (media == null && url != null) p.str("thumbnail_url") else null,
            bodyImages = html?.let(Markdown::images).orEmpty(),
            shareUrl = p.str("ap_id"),
        )
    }

    private fun media(p: JSONObject, pv: JSONObject, url: String): PostMedia? {
        val uri = Uri.parse(url)
        val type = p.str("url_content_type").orEmpty()
        val thumb = p.str("thumbnail_url")
        val details = pv.optJSONObject("image_details")
        val width = details?.optInt("width") ?: 0
        val height = details?.optInt("height") ?: 0
        if (type.startsWith("image/") || Links.isImageFile(uri)) {
            // The instance's own smaller copy for lists; the original for the viewer.
            val small = details?.str("link") ?: thumb ?: url
            return PostMedia.Image(Picture(small, url, width, height))
        }
        val preview = thumb?.let { Picture(it, it, width, height) }
        val file = Links.directVideo(uri)
        if (type.startsWith("video/") || file != null) {
            val (mp4, gifLike) = file ?: (url to false)
            return PostMedia.Video(preview, VideoStream(null, mp4, width, height, 0, gifLike))
        }
        val embedUrl = p.str("embed_video_url")
        if (embedUrl != null) {
            val html = """<iframe src="${embedUrl.replace("\"", "&quot;")}" allow="autoplay; encrypted-media; fullscreen" allowfullscreen></iframe>"""
            return PostMedia.Video(preview, null, VideoEmbed(html, 0, 0, uri.host?.removePrefix("www.")))
        }
        if (Links.isVideoPage(uri)) return PostMedia.Video(preview, null)
        return null
    }

    /** One comment, from a CommentView. [op] is the post's author, to mark their comments. */
    fun comment(cv: JSONObject, depth: Int, parentName: String, op: String): Comment {
        val c = cv.getJSONObject("comment")
        val author = person(cv.optJSONObject("creator"))
        val body = when {
            c.optBoolean("removed") -> "*[removed]*"
            c.optBoolean("deleted") -> "*[deleted]*"
            else -> c.optString("content")
        }
        val html = Markdown.toHtml(body)
        return Comment(
            name = "c${c.getLong("id")}",
            author = author,
            bodyHtml = html,
            score = cv.optJSONObject("counts")?.optInt("score") ?: 0,
            scoreHidden = false,
            likes = myVote(cv),
            saved = cv.optBoolean("saved"),
            createdUtc = time(c.str("published")),
            depth = depth,
            parentName = parentName,
            isOp = author == op,
            stickied = false,
            distinguished = when {
                c.optBoolean("distinguished") -> "moderator"
                cv.optBoolean("creator_is_admin") -> "admin"
                else -> null
            },
            flair = null,
            images = Markdown.images(html),
        )
    }

    /**
     * Lemmy sends comments as a flat list; each one's path ("0.12.34") gives its
     * ancestors. This arranges the ones under [rootParent] (a post or comment name) as
     * a thread starting at [baseDepth]. Comments with replies that weren't sent (past
     * the depth limit) get a [More] row that loads them in place.
     */
    fun tree(comments: JSONArray, postName: String, rootParent: String, baseDepth: Int, op: String): List<ThreadItem> {
        val views = (0 until comments.length()).map { comments.getJSONObject(it) }
        val byParent = views.groupBy { parentOf(it, postName) }
        val out = mutableListOf<ThreadItem>()
        val placed = HashSet<String>()

        fun place(parent: String, depth: Int) {
            byParent[parent]?.forEach { cv ->
                val comment = comment(cv, depth, parent, op)
                if (!placed.add(comment.name)) return@forEach
                out += comment
                if (byParent.containsKey(comment.name)) {
                    place(comment.name, depth + 1)
                } else {
                    val replies = cv.optJSONObject("counts")?.optInt("child_count") ?: 0
                    if (replies > 0) {
                        out += More(
                            name = "more:${comment.name}",
                            children = listOf(comment.name.removePrefix("c")),
                            count = replies,
                            depth = depth + 1,
                            parentName = comment.name,
                        )
                    }
                }
            }
        }
        place(rootParent, baseDepth)
        return out
    }

    /** The name of a comment's parent: the post for top-level comments. */
    fun parentOf(cv: JSONObject, postName: String): String {
        val path = cv.optJSONObject("comment")?.optString("path").orEmpty().split('.')
        return if (path.size <= 2) postName else "c" + path[path.size - 2]
    }

    /** A CommunityView from /community/list, for Discover. */
    fun communityInfo(cv: JSONObject): CommunityInfo {
        val c = cv.getJSONObject("community")
        val counts = cv.optJSONObject("counts")
        val name = community(c)
        return CommunityInfo(
            name = name,
            title = c.str("title")?.takeIf { !it.equals(c.optString("name"), ignoreCase = true) },
            description = c.str("description")?.let(Markdown::plain).orEmpty(),
            subscribers = counts?.optInt("subscribers") ?: 0,
            activeUsers = counts?.optInt("users_active_week", -1)?.takeIf { it >= 0 },
            icon = c.str("icon"),
            banner = c.str("banner"),
            over18 = c.optBoolean("nsfw"),
            subscribed = cv.optString("subscribed") == "Subscribed",
        )
    }

    /** A community's name: "name" if it's on this instance, else "name@host". */
    fun community(c: JSONObject): String = qualified(c)

    fun person(p: JSONObject?): String = p?.let(::qualified) ?: "[deleted]"

    private fun qualified(o: JSONObject): String {
        val name = o.optString("name")
        if (o.optBoolean("local", true)) return name
        val host = o.str("actor_id")?.let { Uri.parse(it).host } ?: return name
        return "$name@$host"
    }

    private fun myVote(v: JSONObject): Boolean? = when (v.optInt("my_vote", 0)) {
        1 -> true
        -1 -> false
        else -> null
    }

    /** Lemmy's timestamps: ISO 8601, with a zone since 0.19 ("2024-01-02T03:04:05.123456Z"). */
    fun time(raw: String?): Long {
        if (raw == null) return 0
        return runCatching { OffsetDateTime.parse(raw).toEpochSecond() }
            .recoverCatching { Instant.parse(raw + "Z").epochSecond }
            .getOrDefault(0)
    }

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
