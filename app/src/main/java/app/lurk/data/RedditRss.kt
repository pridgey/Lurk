package app.lurk.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.text.Html
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** A page of the RSS front page: the posts, when they were fetched, and anything to tell the reader. */
class RssPage(val page: FeedPage, val fetchedAt: Long, val notice: String?)

/**
 * The account's Reddit front page from its private RSS feed (reddit.com/prefs/feeds), for
 * while Reddit blocks Lurk's browser. The feed needs no sign-in and, unlike public RSS
 * feeds (one request a minute), showed no rate limit in testing. RSS has posts only: no
 * scores, comment counts or comments.
 *
 * Paced like an RSS reader: the first page is kept on the phone and shown at once; it's
 * fetched again when it's over [FRESH_MS] old, or on a pull (at most once per [MIN_PULL_MS]).
 * A 429 backs off for as long as Reddit says, doubling each time.
 */
class RedditRss(context: Context, accountId: String, private val build: Int, private val log: DebugLog) {

    private val prefs = context.getSharedPreferences("reddit_rss", Context.MODE_PRIVATE)
    private val key = "link:$accountId"
    private val cacheFile = File(context.cacheDir, "rss-front-$accountId.xml")

    /** The feed link, as [RssProbe.parseFrontPage] gives it. Null: not set up. Kept on this phone. */
    var link by mutableStateOf(prefs.getString(key, null))
        private set

    fun changeLink(url: String?) {
        if (url == link) return
        link = url
        prefs.edit().apply { if (url == null) remove(key) else putString(key, url) }.apply()
        cacheFile.delete()
        cached = null
    }

    private class Cached(val xml: String, val fetchedAt: Long)

    private var cached: Cached? = null
        get() = field ?: runCatching {
            cacheFile.takeIf { it.exists() }?.let { Cached(it.readText(), it.lastModified()) }
        }.getOrNull().also { field = it }

    /** Whether the saved first page is old enough to fetch again without being asked. */
    val isStale: Boolean get() = (cached?.fetchedAt ?: 0L).let { System.currentTimeMillis() - it > FRESH_MS }

    private val mutex = Mutex()
    private var lastRequestAt = 0L
    private var backoffUntil = 0L
    private var backoffMs = 0L

    /**
     * A page of posts. [cursor] null is the first page; [force] is a pull to refresh,
     * which still uses the saved page when it's under a minute old.
     */
    suspend fun page(cursor: String?, force: Boolean): RssPage = mutex.withLock {
        val url = link ?: throw ServiceException("Add your front page feed link first")
        val saved = if (cursor == null) cached else null
        val age = saved?.let { System.currentTimeMillis() - it.fetchedAt }
        if (saved != null && age!! < (if (force) MIN_PULL_MS else FRESH_MS)) {
            return@withLock fromXml(saved.xml, saved.fetchedAt, null)
        }
        val wait = backoffUntil - SystemClock.elapsedRealtime()
        if (wait > 0) {
            val msg = "Reddit asked Lurk to slow down. Try again in ${minutes(wait)}."
            if (saved != null) return@withLock fromXml(saved.xml, saved.fetchedAt, msg)
            throw ServiceException(msg)
        }

        val gap = lastRequestAt + REQUEST_GAP_MS - SystemClock.elapsedRealtime()
        if (gap > 0) delay(gap)
        lastRequestAt = SystemClock.elapsedRealtime()

        val result = fetch("$url&limit=$PAGE" + (cursor?.let { "&after=$it" } ?: ""))
        when {
            result.status == 200 -> {
                backoffMs = 0
                val now = System.currentTimeMillis()
                if (cursor == null) {
                    runCatching { cacheFile.writeText(result.body) }
                    cached = Cached(result.body, now)
                }
                fromXml(result.body, now, null)
            }
            result.status == 429 -> {
                backoffMs = (backoffMs * 2).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
                    .coerceAtLeast(result.retryAfterMs ?: 0)
                backoffUntil = SystemClock.elapsedRealtime() + backoffMs
                log.note("RSS: rate limited, waiting ${minutes(backoffMs)}")
                val msg = "Reddit asked Lurk to slow down. Try again in ${minutes(backoffMs)}."
                if (saved != null) fromXml(saved.xml, saved.fetchedAt, msg) else throw ServiceException(msg)
            }
            else -> {
                val msg = when (result.status) {
                    0 -> "Couldn't reach Reddit. Check your connection."
                    401, 403, 404 -> "Reddit didn't accept the feed link. It changes if you sign out everywhere; paste a new one."
                    in 300..399 -> "Reddit didn't accept the feed link. Paste a new one from reddit.com/prefs/feeds."
                    else -> "Reddit returned an error (${result.status})."
                }
                if (saved != null) fromXml(saved.xml, saved.fetchedAt, "$msg Showing what Lurk saved.") else throw ServiceException(msg)
            }
        }
    }

    private fun fromXml(xml: String, fetchedAt: Long, notice: String?): RssPage {
        val posts = runCatching { parse(xml) }.getOrElse {
            throw ServiceException("Reddit sent a feed Lurk couldn't read")
        }
        // A short page is the end.
        val after = posts.lastOrNull()?.name?.takeIf { posts.size >= PAGE / 2 }
        return RssPage(FeedPage(posts, after), fetchedAt, notice)
    }

    private class Fetched(val status: Int, val body: String, val retryAfterMs: Long?)

    private suspend fun fetch(url: String): Fetched = withContext(Dispatchers.IO) {
        val shown = url.replace(Regex("feed=[^&]+"), "feed=…").removePrefix("https://www.reddit.com")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Lurk/$build (personal RSS reader; Android)")
            val status = conn.responseCode
            val body = if (status == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
            val retry = listOf("retry-after", "x-ratelimit-reset")
                .firstNotNullOfOrNull { conn.getHeaderField(it)?.toDoubleOrNull() }?.let { (it * 1000).toLong() }
            log.note("RSS $shown -> HTTP $status")
            Fetched(status, body, retry)
        } catch (e: Exception) {
            log.note("RSS $shown -> failed: ${e.message}")
            Fetched(0, "", null)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Opening the app shows the saved page if it's newer than this; older, it's fetched again. */
        const val FRESH_MS = 10 * 60_000L
        /** A pull within this long of the last fetch shows the saved page. */
        const val MIN_PULL_MS = 60_000L
        private const val REQUEST_GAP_MS = 2_000L
        private const val MIN_BACKOFF_MS = 2 * 60_000L
        private const val MAX_BACKOFF_MS = 30 * 60_000L
        private const val PAGE = 100

        private fun minutes(ms: Long): String {
            val m = (ms + 59_999) / 60_000
            return if (m <= 1) "a minute" else "$m minutes"
        }

        private val linkRe = Regex("""<a href="([^"]+)">\[link]</a>""")
        private val imgRe = Regex("""<img src="([^"]+)"""")
        private val selfRe = Regex("""<!-- SC_OFF -->(.*?)<!-- SC_ON -->""", RegexOption.DOT_MATCHES_ALL)
        private val imageExt = Regex("""\.(jpe?g|png|webp|gif)$""", RegexOption.IGNORE_CASE)

        /** Reddit's Atom feed as posts. */
        fun parse(xml: String): List<Post> {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            val posts = ArrayList<Post>()
            var entry: MutableMap<String, String>? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    val tag = parser.name
                    when {
                        tag == "entry" -> entry = HashMap()
                        entry == null -> Unit
                        tag == "category" -> entry["community"] = parser.getAttributeValue(null, "term").orEmpty()
                        tag == "link" -> entry["link"] = parser.getAttributeValue(null, "href").orEmpty()
                        tag == "media:thumbnail" -> entry["thumb"] = parser.getAttributeValue(null, "url").orEmpty()
                        tag in setOf("id", "title", "name", "published", "content") -> entry[tag] = parser.nextText()
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "entry") {
                    entry?.let { e -> post(e)?.let(posts::add) }
                    entry = null
                }
            }
            return posts
        }

        private fun post(e: Map<String, String>): Post? {
            val name = e["id"]?.takeIf { it.startsWith("t3_") } ?: return null
            val permalink = e["link"].orEmpty()
            val ref = Uri.parse(permalink).path ?: return null
            val content = e["content"].orEmpty()
            val target = linkRe.find(content)?.groupValues?.get(1)?.let(::unescape) ?: permalink
            val selfHtml = selfRe.find(content)?.groupValues?.get(1)?.trim()
            val isSelf = target == permalink || selfHtml != null
            val uri = Uri.parse(target)
            val host = uri.host.orEmpty().lowercase()
            val thumb = (e["thumb"] ?: imgRe.find(content)?.groupValues?.get(1)?.let(::unescape))
                ?.takeIf { it.startsWith("https://") }
            val media: PostMedia? = when {
                isSelf -> null
                (host == "i.redd.it" || host == "i.imgur.com") && imageExt.containsMatchIn(uri.path.orEmpty()) -> {
                    // A preview that isn't cropped beats the full file in a list; cropped ones would mislead.
                    val list = thumb?.takeIf { "crop=" !in it && "width=140" !in it } ?: target
                    PostMedia.Image(Picture(list, target, 0, 0))
                }
                host == "v.redd.it" -> PostMedia.Video(thumb?.let { Picture(it, it, 0, 0) }, stream = null)
                else -> null
            }
            val selftext = selfHtml?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim() }.orEmpty()
            return Post(
                name = name,
                id = name.removePrefix("t3_"),
                title = e["title"].orEmpty(),
                author = e["name"].orEmpty().removePrefix("/u/"),
                community = e["community"]?.takeIf { it.isNotBlank() },
                score = 0,
                likes = null,
                saved = false,
                numComments = 0,
                createdUtc = runCatching { OffsetDateTime.parse(e["published"]).toEpochSecond() }.getOrDefault(0L),
                threadRef = ref,
                url = target,
                domain = if (isSelf) "self.${e["community"].orEmpty()}" else host,
                isSelf = isSelf,
                selftext = selftext,
                selftextHtml = selfHtml,
                flair = null,
                over18 = false, // RSS doesn't say
                spoiler = false,
                stickied = false,
                locked = false,
                media = media,
                thumbnail = thumb,
                shareUrl = permalink,
                hasStats = false,
            )
        }

        private fun unescape(s: String) = s.replace("&amp;", "&")
    }
}
