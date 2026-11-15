package app.lurk.data

import org.json.JSONArray
import org.json.JSONObject

/** A [Post] as JSON and back, for keeping posts on the phone (Read later). */
object PostCodec {

    fun encode(p: Post): JSONObject = JSONObject()
        .put("name", p.name).put("id", p.id).put("title", p.title).put("author", p.author)
        .put("community", p.community ?: JSONObject.NULL)
        .put("score", p.score).put("likes", p.likes ?: JSONObject.NULL).put("saved", p.saved)
        .put("numComments", p.numComments).put("createdUtc", p.createdUtc).put("threadRef", p.threadRef)
        .put("url", p.url).put("domain", p.domain).put("isSelf", p.isSelf).put("selftext", p.selftext)
        .put("selftextHtml", p.selftextHtml ?: JSONObject.NULL).put("flair", p.flair ?: JSONObject.NULL)
        .put("over18", p.over18).put("spoiler", p.spoiler).put("stickied", p.stickied).put("locked", p.locked)
        .put("media", p.media?.let(::media) ?: JSONObject.NULL)
        .put("thumbnail", p.thumbnail ?: JSONObject.NULL)
        .put("bodyImages", JSONArray(p.bodyImages.map(::inline)))
        .put("shareUrl", p.shareUrl ?: JSONObject.NULL)
        .put("hasStats", p.hasStats)

    fun decode(o: JSONObject): Post = Post(
        name = o.getString("name"),
        id = o.optString("id"),
        title = o.optString("title"),
        author = o.optString("author"),
        community = o.str("community"),
        score = o.optInt("score"),
        likes = if (o.isNull("likes") || !o.has("likes")) null else o.optBoolean("likes"),
        saved = o.optBoolean("saved"),
        numComments = o.optInt("numComments"),
        createdUtc = o.optLong("createdUtc"),
        threadRef = o.optString("threadRef"),
        url = o.optString("url"),
        domain = o.optString("domain"),
        isSelf = o.optBoolean("isSelf"),
        selftext = o.optString("selftext"),
        selftextHtml = o.str("selftextHtml"),
        flair = o.str("flair"),
        over18 = o.optBoolean("over18"),
        spoiler = o.optBoolean("spoiler"),
        stickied = o.optBoolean("stickied"),
        locked = o.optBoolean("locked"),
        media = o.optJSONObject("media")?.let(::media),
        thumbnail = o.str("thumbnail"),
        bodyImages = o.optJSONArray("bodyImages")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::inline) } }.orEmpty(),
        shareUrl = o.str("shareUrl"),
        hasStats = o.optBoolean("hasStats", true),
    )

    private fun media(m: PostMedia): JSONObject = when (m) {
        is PostMedia.Image -> JSONObject().put("type", "image").put("picture", picture(m.picture))
        is PostMedia.Gallery -> JSONObject().put("type", "gallery").put("pictures", JSONArray(m.pictures.map(::picture)))
        is PostMedia.Video -> JSONObject().put("type", "video")
            .put("preview", m.preview?.let(::picture) ?: JSONObject.NULL)
            .put("stream", m.stream?.let(::stream) ?: JSONObject.NULL)
            .put("embed", m.embed?.let(::embed) ?: JSONObject.NULL)
    }

    private fun media(o: JSONObject): PostMedia? = when (o.optString("type")) {
        "image" -> o.optJSONObject("picture")?.let(::picture)?.let { PostMedia.Image(it) }
        "gallery" -> o.optJSONArray("pictures")
            ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::picture) } }
            ?.takeIf { it.isNotEmpty() }?.let { PostMedia.Gallery(it) }
        "video" -> PostMedia.Video(
            o.optJSONObject("preview")?.let(::picture),
            o.optJSONObject("stream")?.let(::stream),
            o.optJSONObject("embed")?.let(::embed),
        )
        else -> null
    }

    private fun picture(p: Picture) = JSONObject().put("url", p.url).put("fullUrl", p.fullUrl).put("w", p.width).put("h", p.height)
    private fun picture(o: JSONObject) = Picture(o.optString("url"), o.optString("fullUrl"), o.optInt("w"), o.optInt("h"))

    private fun stream(s: VideoStream) = JSONObject()
        .put("hls", s.hlsUrl ?: JSONObject.NULL).put("mp4", s.mp4Url ?: JSONObject.NULL)
        .put("w", s.width).put("h", s.height).put("duration", s.durationSec).put("gif", s.isGif)
        .put("audio", s.hasAudio ?: JSONObject.NULL)

    private fun stream(o: JSONObject) = VideoStream(
        hlsUrl = o.str("hls"), mp4Url = o.str("mp4"), width = o.optInt("w"), height = o.optInt("h"),
        durationSec = o.optInt("duration"), isGif = o.optBoolean("gif"),
        hasAudio = if (o.isNull("audio") || !o.has("audio")) null else o.optBoolean("audio"),
    )

    private fun embed(e: VideoEmbed) = JSONObject().put("html", e.html).put("w", e.width).put("h", e.height).put("provider", e.provider ?: JSONObject.NULL)
    private fun embed(o: JSONObject) = VideoEmbed(o.optString("html"), o.optInt("w"), o.optInt("h"), o.str("provider"))

    private fun inline(i: InlineImage) = JSONObject().put("id", i.id).put("url", i.url).put("fullUrl", i.fullUrl).put("w", i.width).put("h", i.height)
    private fun inline(o: JSONObject) = InlineImage(o.optString("id"), o.optString("url"), o.optString("fullUrl"), o.optInt("w"), o.optInt("h"))

    private fun JSONObject.str(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
}
