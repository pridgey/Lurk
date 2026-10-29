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

/** Reddit no longer accepts the front page's feed link. */
class FeedLinkRejected(message: String) : ServiceException(message)

/**
 * Reddit's RSS feeds for one account: the front page's private feed (from
 * reddit.com/prefs/feeds) and subreddit feeds. RSS has posts only: no scores, comment
 * counts or comments.
 *
 * The private feed showed no rate limit in testing. Public feeds get one request a
 * minute, so subreddit requests wait their turn ([waiting] says so) instead of being
 * refused. Subreddit requests carry the feed link's credentials too, in case that lifts
 * the limit; if Reddit refuses them, they go without.
 *
 * Paced like an RSS reader: each feed's first page is kept on the phone and shown at
 * once; it's fetched again when it's over [FRESH_MS] old, or on a pull (at most once per
 * [MIN_PULL_MS]). When a feed would have to wait for the limit and a saved copy exists,
 * the saved copy shows instead.
 */
class RedditRss(context: Context, accountId: String, private val build: Int, private val log: DebugLog) {

    /** The front page feed link, as [RssProbe.parseFrontPage] gives it. Null: not set up. */
    var link: String? = null
        set(value) {
            // A different link is someone else's front page; setting the first one isn't a change.
            if (field != null && value != field) clearCache()
            field = value
        }

    /** What a feed is waiting on right now, with a countdown. */
    var waiting by mutableStateOf<String?>(null)
        private set

    private val cacheDir = File(context.cacheDir, "rss/$accountId")

    private class Cached(val xml: String, val fetchedAt: Long)

    private val cached = HashMap<String, Cached?>()

    private fun key(community: String?) =
        community?.lowercase()?.replace(Regex("[^a-z0-9_+]"), "")?.let { "r_$it" } ?: "front"

    private fun saved(key: String): Cached? = cached.getOrPut(key) {
        runCatching {
            File(cacheDir, "$key.xml").takeIf { it.exists() }?.let { Cached(it.readText(), it.lastModified()) }
        }.getOrNull()
    }

    private fun save(key: String, xml: String, at: Long) {
        cached[key] = Cached(xml, at)
        runCatching {
            cacheDir.mkdirs()
            File(cacheDir, "$key.xml").writeText(xml)
            // Keep the most recent feeds only.
            cacheDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_SAVED)?.forEach { it.delete() }
        }
    }

    fun clearCache() {
        cached.clear()
        runCatching { cacheDir.deleteRecursively() }
    }

    /** Whether [community]'s saved first page (Home for null) is old enough to fetch again unasked. */
    fun isStale(community: String?): Boolean =
        (saved(key(community))?.fetchedAt ?: 0L).let { System.currentTimeMillis() - it > FRESH_MS }

    private val mutex = Mutex()
    private var lastRequestAt = 0L
    /** The front page's own backoff, after a 429. */
    private var frontBackoffUntil = 0L
    private var frontBackoffMs = 0L
    /** When the public feeds' limit lets the next request through (elapsedRealtime). */
    private var publicReadyAt = 0L
    /** Whether subreddit feeds accept the feed link's credentials. Null: not tried yet. */
    private var subredditAuth: Boolean? = null

    /**
     * A page of [community]'s posts (Home for null). [cursor] null is the first page;
     * [force] is a pull to refresh.
     */
    suspend fun page(community: String?, cursor: String?, force: Boolean): FeedPage {
        val link = link ?: throw ServiceException("Add your front page feed link first")
        val key = key(community)
        val saved = if (cursor == null) saved(key) else null
        val age = saved?.let { System.currentTimeMillis() - it.fetchedAt }
        if (saved != null && age!! < (if (force) MIN_PULL_MS else FRESH_MS)) return fromXml(saved, null)

        return if (community == null) front(link, cursor, saved) else subreddit(link, community, key, cursor, saved, force)
    }

    /**
     * [community]'s search feed for [query] (newest first, the last week), kept like a
     * first page. It counts against the same one-a-minute limit as subreddit feeds.
     */
    suspend fun search(community: String, query: String, force: Boolean): FeedPage {
        val link = link ?: throw ServiceException("Add your front page feed link first")
        val key = "g_" + key(community)
        val saved = saved(key)
        val age = saved?.let { System.currentTimeMillis() - it.fetchedAt }
        if (saved != null && age!! < (if (force) MIN_PULL_MS else FRESH_MS)) return fromXml(saved, null)
        return subreddit(
            link, community, key, null, saved, force,
            path = "/search.rss",
            query = "q=${Uri.encode(query)}&restrict_sr=on&sort=new&t=week&",
        )
    }

    private suspend fun front(link: String, cursor: String?, saved: Cached?): FeedPage {
        val wait = frontBackoffUntil - SystemClock.elapsedRealtime()
        if (wait > 0) return savedOr(saved, "Reddit asked Lurk to slow down. Try again in ${minutes(wait)}.")

        val result = request("$link&limit=$PAGE" + (cursor?.let { "&after=$it" } ?: ""))
        return when (result.status) {
            200 -> {
                frontBackoffMs = 0
                fresh("front", result.body, cursor)
            }
            429 -> {
                frontBackoffMs = (frontBackoffMs * 2).coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
                    .coerceAtLeast(result.retryAfterMs ?: 0)
                frontBackoffUntil = SystemClock.elapsedRealtime() + frontBackoffMs
                log.note("RSS: rate limited, waiting ${minutes(frontBackoffMs)}")
                savedOr(saved, "Reddit asked Lurk to slow down. Try again in ${minutes(frontBackoffMs)}.")
            }
            401, 403, 404, in 300..399 -> throw FeedLinkRejected(
                "Reddit no longer accepts this feed link. It changes when you sign out everywhere; " +
                    "sign in again with a new one from reddit.com/prefs/feeds.",
            )
            else -> savedOr(saved, failure(result.status))
        }
    }

    private suspend fun subreddit(
        link: String,
        community: String,
        key: String,
        cursor: String?,
        saved: Cached?,
        force: Boolean,
        path: String = "/.rss",
        query: String = "",
    ): FeedPage {
        // Waiting on the limit with a saved copy to hand: show that, unless this is a pull.
        val wait = publicReadyAt - SystemClock.elapsedRealtime()
        if (saved != null && !force && wait > SHOW_SAVED_AFTER_MS) {
            return fromXml(saved, "Saved ${ago(saved.fetchedAt)}. Reddit allows one subreddit feed a minute; pull for new posts after that.")
        }
        val credentials = link.substringAfter('?')
        fun url(auth: Boolean) = "https://www.reddit.com/r/${Uri.encode(community, "+")}$path?" +
            (if (auth) "$credentials&" else "") + query + "limit=$PAGE" + (cursor?.let { "&after=$it" } ?: "")

        var auth = subredditAuth != false
        repeat(3) {
            awaitPublic()
            val result = request(url(auth))
            when {
                result.status == 200 -> {
                    if (auth) subredditAuth = true
                    return fresh(key, result.body, cursor)
                }
                result.status == 429 -> {
                    // Waits once more if it's a short one; past that, give up for now.
                    if (publicReadyAt - SystemClock.elapsedRealtime() > MAX_WAIT_MS) {
                        return savedOr(saved, "Reddit is limiting subreddit feeds right now. Try again in a minute or two.")
                    }
                }
                auth && result.status != 0 && result.status != 404 -> {
                    log.note("RSS: subreddit feeds don't take the feed link; trying without")
                    subredditAuth = false
                    auth = false
                }
                result.status == 404 || result.status in 300..399 ->
                    throw ServiceException("Reddit has no feed for r/$community. It may be private or banned.")
                else -> return savedOr(saved, failure(result.status))
            }
        }
        return savedOr(saved, "Reddit is limiting subreddit feeds right now. Try again in a minute or two.")
    }

    /** Waits for the public limit, counting down in [waiting]. */
    private suspend fun awaitPublic() {
        try {
            while (true) {
                val left = publicReadyAt - SystemClock.elapsedRealtime()
                if (left <= 0) break
                waiting = "Reddit allows one subreddit feed a minute. Loading in ${(left + 999) / 1000} s…"
                delay(left.coerceAtMost(1_000))
            }
        } finally {
            waiting = null
        }
    }

    private fun fresh(key: String, xml: String, cursor: String?): FeedPage {
        val now = System.currentTimeMillis()
        val page = fromXml(Cached(xml, now), null)
        if (cursor == null) save(key, xml, now)
        return page
    }

    private fun savedOr(saved: Cached?, message: String): FeedPage =
        if (saved != null) fromXml(saved, message) else throw ServiceException(message)

    private fun fromXml(c: Cached, notice: String?): FeedPage {
        val posts = runCatching { parse(c.xml) }.getOrElse {
            throw ServiceException("Reddit sent a feed Lurk couldn't read")
        }
        // A short page is the end.
        val after = posts.lastOrNull()?.name?.takeIf { posts.size >= PAGE / 2 }
        return FeedPage(posts, after, fetchedAt = c.fetchedAt, notice = notice)
    }

    private fun failure(status: Int) = when (status) {
        0 -> "Couldn't reach Reddit. Check your connection."
        else -> "Reddit returned an error ($status)."
    }

    private class Fetched(val status: Int, val body: String, val retryAfterMs: Long?)

    /** One request, spaced from the last, noting what Reddit says about the public limit. */
    private suspend fun request(url: String): Fetched = mutex.withLock {
        val gap = lastRequestAt + REQUEST_GAP_MS - SystemClock.elapsedRealtime()
        if (gap > 0) delay(gap)
        lastRequestAt = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) { fetch(url) }.also { r ->
            if (r.status == 429) {
                publicReadyAt = SystemClock.elapsedRealtime() + (r.retryAfterMs ?: 60_000L).coerceAtLeast(5_000L)
            }
        }
    }

    private fun fetch(url: String): Fetched {
        val shown = url.replace(Regex("feed=[^&]+"), "feed=…").removePrefix("https://www.reddit.com")
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Lurk/$build (personal RSS reader; Android)")
            val status = conn.responseCode
            val body = if (status == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
            val remaining = conn.getHeaderField("x-ratelimit-remaining")?.toDoubleOrNull()
            val reset = conn.getHeaderField("x-ratelimit-reset")?.toDoubleOrNull()
            // Used up: the next public request waits for the window to reset.
            if (remaining != null && remaining < 1 && reset != null) {
                publicReadyAt = SystemClock.elapsedRealtime() + (reset * 1000).toLong() + 500
            }
            val retry = (conn.getHeaderField("retry-after")?.toDoubleOrNull() ?: reset)?.let { (it * 1000).toLong() }
            log.note("RSS $shown -> HTTP $status" + (remaining?.let { " (limit left: ${it.toInt()}, resets in ${reset?.toInt()} s)" } ?: ""))
            Fetched(status, body, retry)
        } catch (e: Exception) {
            log.note("RSS $shown -> failed: ${e.message}")
            Fetched(0, "", null)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** A saved page newer than this shows without fetching; older, it's fetched again. */
        const val FRESH_MS = 10 * 60_000L
        /** A pull within this long of the last fetch shows the saved page. */
        const val MIN_PULL_MS = 60_000L
        private const val REQUEST_GAP_MS = 2_000L
        private const val MIN_BACKOFF_MS = 2 * 60_000L
        private const val MAX_BACKOFF_MS = 30 * 60_000L
        /** Longer than this waiting for the limit, a saved copy shows instead. */
        private const val SHOW_SAVED_AFTER_MS = 3_000L
        /** Longer than this, stop waiting and say so. */
        private const val MAX_WAIT_MS = 90_000L
        private const val MAX_SAVED = 60
        private const val PAGE = 100

        private fun minutes(ms: Long): String {
            val m = (ms + 59_999) / 60_000
            return if (m <= 1) "a minute" else "$m minutes"
        }

        private fun ago(at: Long): String {
            val m = (System.currentTimeMillis() - at) / 60_000
            return when {
                m < 1 -> "just now"
                m < 60 -> "$m min ago"
                else -> "${m / 60} h ago"
            }
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
