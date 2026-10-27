package app.lurk.data


/** One image, at the size shown in lists ([url]) and full size for the viewer ([fullUrl]). */
data class Picture(val url: String, val fullUrl: String, val width: Int, val height: Int) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1.5f
}

/**
 * A video Reddit hosts or has converted: [hlsUrl] carries sound, [mp4Url] is a
 * video-only fallback. [isGif] clips loop and start muted. Some still carry sound:
 * [hasAudio] is Reddit's word on that when it gives one, null when it doesn't.
 */
data class VideoStream(
    val hlsUrl: String?,
    val mp4Url: String?,
    val width: Int,
    val height: Int,
    val durationSec: Int,
    val isGif: Boolean,
    val hasAudio: Boolean? = null,
)

/** Another site's player, as the iframe HTML Reddit gives for embedding it. */
data class VideoEmbed(
    val html: String,
    val width: Int,
    val height: Int,
    /** "YouTube", "Vimeo", ... */
    val provider: String?,
)

/** The big media shown with a post. Plain links use [Post.thumbnail] instead. */
sealed interface PostMedia {
    data class Image(val picture: Picture) : PostMedia
    data class Gallery(val pictures: List<Picture>) : PostMedia
    /**
     * [stream]: a copy Reddit hosts, played natively. Otherwise [embed]: the site's own
     * player (YouTube, Vimeo, ...). Neither: only the site itself can play it.
     */
    data class Video(val preview: Picture?, val stream: VideoStream?, val embed: VideoEmbed? = null) : PostMedia
}

data class Post(
    /** The service's id for votes and saves, e.g. Reddit's fullname t3_abc123. Unique per service. */
    val name: String,
    val id: String,
    /** Blank on sites whose posts have no title. */
    val title: String,
    val author: String,
    /** The subreddit or community it's in, or null on sites without them. */
    val community: String?,
    val score: Int,
    /** true = upvoted, false = downvoted, null = no vote. */
    val likes: Boolean?,
    val saved: Boolean,
    val numComments: Int,
    val createdUtc: Long,
    /** What [Service.thread] takes to open it: Reddit's permalink, Lemmy's "post:123". */
    val threadRef: String,
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
    /** Images in the body, shown where it links to them (as in [Comment.images]). */
    val bodyImages: List<InlineImage> = emptyList(),
    /** The post's canonical web page, for sharing, when the site gives one. */
    val shareUrl: String? = null,
    /** False where the source has no score, comment count, votes or saves (Reddit's RSS). */
    val hasStats: Boolean = true,
)

/** A row in a comment thread: a comment, or a "load more" placeholder. */
sealed interface ThreadItem {
    val key: String
    val depth: Int
    val parentName: String
}

/**
 * An image or GIF embedded in a comment. On Reddit [id] is its media_metadata key: an upload's
 * media id, or "giphy|<id>" / "giphy|<id>|downsized" for Giphy. Elsewhere it's the image's URL. [url] is sized for the thread,
 * [fullUrl] for the full-screen viewer.
 */
data class InlineImage(val id: String, val url: String, val fullUrl: String, val width: Int, val height: Int) {
    /** What the body's link to this GIF contains: the bare upload or Giphy id. */
    val token: String get() = if (id.startsWith("giphy|")) id.split('|')[1] else id
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
    /** Images and GIFs embedded in the body (uploads or Giphy), from media_metadata. */
    val images: List<InlineImage> = emptyList(),
) : ThreadItem {
    override val key: String get() = name
}

data class More(
    val name: String,
    /**
     * What the service needs to load these in place (Reddit: comment ids; Lemmy: the parent).
     * Empty means "continue this thread" (needs its own page).
     */
    val children: List<String>,
    val count: Int,
    override val depth: Int,
    override val parentName: String,
) : ThreadItem {
    // "Continue this thread" rows all share one name, so the parent keeps them apart.
    override val key: String get() = "more:$name:$parentName:${children.firstOrNull()}"
}

/**
 * [after]: the cursor for the next page, or null at the end. Services that keep pages
 * (RSS) say when it was fetched ([fetchedAt], ms) and anything to show about that ([notice]).
 */
class FeedPage(val posts: List<Post>, val after: String?, val fetchedAt: Long? = null, val notice: String? = null)

class ThreadData(val post: Post, val items: List<ThreadItem>, val isSingleThread: Boolean)


/** A community to suggest in Discover, with what its card shows. */
data class CommunityInfo(
    /** As feeds and subscriptions name it: "pics", "linux@lemmy.ml". */
    val name: String,
    /** Its display title, when it has one besides the name. */
    val title: String?,
    /** A short plain-text blurb. */
    val description: String,
    /** Null when the site doesn't say. */
    val subscribers: Int?,
    /** People active lately (Reddit: online now; Lemmy: this week), when the site says. */
    val activeUsers: Int?,
    val icon: String?,
    val banner: String?,
    val over18: Boolean,
    val subscribed: Boolean,
)

/** [after]: the cursor for the next page, or null at the end. */
class CommunityPage(val communities: List<CommunityInfo>, val after: String?)
