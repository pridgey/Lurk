package app.lurk.data

import android.content.Context
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Engine debug's "Test RSS": fetches Reddit RSS feeds, spaced out, and logs each status
 * and Reddit's rate-limit headers, to see how many RSS requests this phone's connection
 * gets. A plain request that says it's Lurk, not the WebView.
 *
 * With the account's private front-page feed (from reddit.com/prefs/feeds), it also checks
 * whether that feed gets more posts per request (limit=100), pages (after=), and has a
 * rate limit of its own apart from the public feeds.
 */
object RssProbe {

    private val publicFeeds = listOf(
        "https://www.reddit.com/r/Kotlin/.rss",
        "https://www.reddit.com/r/androiddev/.rss",
        "https://www.reddit.com/r/pics/.rss",
    )
    private const val GAP_MS = 10_000L
    private const val BURST = 6
    private const val BURST_GAP_MS = 1_000L

    /** The saved front-page feed link. It holds a private token, so it stays on this phone. */
    fun savedFrontPage(context: Context): String? =
        prefs(context).getString(KEY_FRONT_PAGE, null)

    fun saveFrontPage(context: Context, url: String?) {
        prefs(context).edit().apply { if (url == null) remove(KEY_FRONT_PAGE) else putString(KEY_FRONT_PAGE, url) }.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("rss_probe", Context.MODE_PRIVATE)
    private const val KEY_FRONT_PAGE = "frontPage"

    /**
     * A pasted front-page feed link as https://www.reddit.com/.rss?feed=…&user=…, or null if
     * it isn't one. Accepts old./new. hosts and the link without "https://".
     */
    fun parseFrontPage(pasted: String): String? {
        val text = pasted.trim()
        val uri = Uri.parse(if ("://" in text) text else "https://$text")
        val host = uri.host?.lowercase() ?: return null
        if (host != "reddit.com" && !host.endsWith(".reddit.com")) return null
        if (uri.path?.trimEnd('/') != "/.rss") return null
        val feed = uri.getQueryParameter("feed")?.takeIf { it.isNotBlank() } ?: return null
        val user = uri.getQueryParameter("user")?.takeIf { it.isNotBlank() } ?: return null
        return Uri.parse("https://www.reddit.com/.rss").buildUpon()
            .appendQueryParameter("feed", feed)
            .appendQueryParameter("user", user)
            .build().toString()
    }

    suspend fun run(log: DebugLog, build: Int, frontPage: String?) {
        if (frontPage == null) {
            log.note("RSS test: ${publicFeeds.size} public feeds, ${GAP_MS / 1000} s apart")
            publicFeeds.forEachIndexed { i, url ->
                if (i > 0) delay(GAP_MS)
                log.note("RSS ${i + 1}: " + fetch(url, build).line)
            }
        } else {
            log.note("RSS test: front page, its next page, a quick burst, then a public feed")
            val first = fetch("$frontPage&limit=100", build)
            log.note("RSS 1 (front page, limit=100): ${first.line}${span(first.ids)}")
            delay(GAP_MS)
            val after = first.ids?.lastOrNull()
            if (after != null) {
                val next = fetch("$frontPage&limit=100&after=$after", build)
                val repeats = next.ids?.count { it in first.ids.orEmpty() }
                log.note("RSS 2 (front page, next page): ${next.line}${span(next.ids)}" +
                    (repeats?.let { ", $it repeated from page 1" } ?: ""))
            } else {
                log.note("RSS 2 (front page again): " + fetch(frontPage, build).line)
            }
            delay(GAP_MS)
            // A quick burst, to find whether the front-page feed has a limit of its own.
            for (i in 1..BURST) {
                val r = fetch(frontPage, build)
                log.note("RSS burst $i/$BURST: ${r.line}")
                if (r.status != 200) break
                delay(BURST_GAP_MS)
            }
            delay(GAP_MS)
            log.note("RSS public: " + fetch(publicFeeds.first(), build).line)
        }
        log.note("RSS test done")
    }

    private class Result(val line: String, val status: Int?, val ids: List<String>?)

    /** ", t3_first … t3_last" for a page of post IDs. */
    private fun span(ids: List<String>?): String =
        if (ids.isNullOrEmpty()) "" else ", ${ids.first()} … ${ids.last()}"

    private val entryId = Regex("<entry>.*?<id>(t3_[a-z0-9]+)</id>", RegexOption.DOT_MATCHES_ALL)

    private suspend fun fetch(url: String, build: Int): Result = withContext(Dispatchers.IO) {
        val shown = redact(url)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Lurk/$build (personal RSS reader; Android)")
            val status = conn.responseCode
            val headers = listOf("x-ratelimit-used", "x-ratelimit-remaining", "x-ratelimit-reset", "retry-after", "location")
                .mapNotNull { h -> conn.getHeaderField(h)?.let { "$h=${redact(it)}" } }
            val ids = if (status == 200) {
                entryId.findAll(conn.inputStream.bufferedReader().use { it.readText() }).map { it.groupValues[1] }.toList()
            } else {
                null
            }
            val line = buildString {
                append(shown).append(" -> HTTP ").append(status)
                ids?.let { append(", ").append(it.size).append(" posts") }
                if (headers.isNotEmpty()) append(" | ").append(headers.joinToString(" "))
            }
            Result(line, status, ids)
        } catch (e: Exception) {
            Result("$shown -> failed: ${e.message}", null, null)
        } finally {
            conn.disconnect()
        }
    }

    /** The URL as logged: the feed token hidden, the host dropped. */
    private fun redact(url: String): String =
        url.replace(Regex("feed=[^&]+"), "feed=…").removePrefix("https://www.reddit.com")
}
