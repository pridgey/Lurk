package app.lurk.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Engine debug's "Test RSS": fetches a few subreddit RSS feeds, spaced out, and logs
 * each status and Reddit's rate-limit headers, to see how many RSS requests this
 * phone's connection gets. A plain request that says it's Lurk, not the WebView.
 */
object RssProbe {

    private val feeds = listOf(
        "https://www.reddit.com/r/Kotlin/.rss",
        "https://www.reddit.com/r/androiddev/.rss",
        "https://www.reddit.com/r/pics/.rss",
    )

    suspend fun run(log: DebugLog, build: Int) {
        log.note("RSS test: ${feeds.size} feeds, 10 s apart")
        feeds.forEachIndexed { i, url ->
            if (i > 0) delay(10_000)
            val line = withContext(Dispatchers.IO) { fetch(url, build) }
            log.note("RSS ${i + 1}: $line")
        }
        log.note("RSS test done")
    }

    private fun fetch(url: String, build: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Lurk/$build (personal RSS reader; Android)")
            val status = conn.responseCode
            val headers = listOf("x-ratelimit-used", "x-ratelimit-remaining", "x-ratelimit-reset", "retry-after", "location")
                .mapNotNull { h -> conn.getHeaderField(h)?.let { "$h=$it" } }
            val entries = if (status == 200) {
                Regex("<entry>").findAll(conn.inputStream.bufferedReader().use { it.readText() }).count()
            } else {
                null
            }
            buildString {
                append(url.removePrefix("https://www.reddit.com")).append(" -> HTTP ").append(status)
                entries?.let { append(", ").append(it).append(" posts") }
                if (headers.isNotEmpty()) append(" | ").append(headers.joinToString(" "))
            }
        } catch (e: Exception) {
            "${url.removePrefix("https://www.reddit.com")} -> failed: ${e.message}"
        } finally {
            conn.disconnect()
        }
    }
}
