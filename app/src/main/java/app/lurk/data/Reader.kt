package app.lurk.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lurk.web.decodeJsResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** One piece of an article in reader view. Text is cut-down HTML: links, bold, italics, code. */
sealed interface ArticleBlock {
    data class Heading(val level: Int, val html: String) : ArticleBlock
    data class Paragraph(val html: String) : ArticleBlock
    data class Quote(val html: String) : ArticleBlock
    data class ListItem(val html: String, val ordered: Boolean, val number: Int, val depth: Int) : ArticleBlock
    data class Code(val text: String) : ArticleBlock
    data class Image(val url: String, val caption: String?, val width: Int, val height: Int) : ArticleBlock
    data object Rule : ArticleBlock
}

/** An article as reader view shows it. */
data class Article(
    val url: String,
    val title: String?,
    val byline: String?,
    val site: String?,
    /** As the page gave it, e.g. "2026-11-02T09:00:00Z"; null when it didn't. */
    val published: String?,
    val words: Int,
    val blocks: List<ArticleBlock>,
) {
    /** Minutes to read at an easy 230 words a minute. */
    val minutes: Int get() = (words / 230).coerceAtLeast(1)

    val host: String get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")

    companion object {
        /** The JSON reader.js returns, or null when it found no article. */
        fun parse(url: String, o: JSONObject): Article? {
            if (!o.optBoolean("ok")) return null
            val arr = o.optJSONArray("blocks") ?: JSONArray()
            val blocks = (0 until arr.length()).mapNotNull { i -> block(arr.optJSONObject(i)) }
            if (blocks.none { it is ArticleBlock.Paragraph || it is ArticleBlock.ListItem }) return null
            return Article(
                url = url,
                title = o.text("title"),
                byline = o.text("byline"),
                site = o.text("site"),
                published = o.text("published"),
                words = o.optInt("words"),
                blocks = blocks,
            )
        }

        private fun block(b: JSONObject?): ArticleBlock? = when (b?.optString("t")) {
            "h" -> ArticleBlock.Heading(b.optInt("l", 2), b.optString("html"))
            "p" -> ArticleBlock.Paragraph(b.optString("html"))
            "q" -> ArticleBlock.Quote(b.optString("html"))
            "li" -> ArticleBlock.ListItem(b.optString("html"), b.optBoolean("o"), b.optInt("n", 1), b.optInt("d"))
            "pre" -> ArticleBlock.Code(b.optString("text"))
            "img" -> b.text("src")?.let { ArticleBlock.Image(it, b.text("cap"), b.optInt("w"), b.optInt("h")) }
            "hr" -> ArticleBlock.Rule
            else -> null
        }

        private fun JSONObject.text(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}

/**
 * Reader view's engine: fetches a page and finds the article in it on the phone, with
 * Mozilla's Readability running in a WebView that's never shown and loads nothing.
 */
class ArticleReader(private val context: Context) {

    private val script: String by lazy {
        val assets = context.applicationContext.assets
        assets.open("reader/Readability.js").bufferedReader().use { it.readText() } + "\n" +
            assets.open("reader/reader.js").bufferedReader().use { it.readText() }
    }

    suspend fun read(url: String): Article {
        val (finalUrl, html) = withContext(Dispatchers.IO) { fetch(url) }
        val result = withContext(Dispatchers.Main) { withTimeout(PARSE_TIMEOUT_MS) { parse(html, finalUrl) } }
        return result?.let { Article.parse(finalUrl, it) }
            ?: throw ServiceException("Couldn't find an article on this page.")
    }

    /** The page's HTML, following redirects; throws for anything that isn't a web page. */
    private fun fetch(start: String): Pair<String, String> {
        var url = start
        repeat(MAX_REDIRECTS) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
            conn.setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val next = conn.getHeaderField("Location") ?: throw IOException("The page moved, but didn't say where.")
                    url = URL(URL(url), next).toString()
                    return@repeat
                }
                if (code != 200) throw ServiceException("The site answered with an error ($code).")
                val type = conn.contentType.orEmpty().lowercase()
                if (type.isNotEmpty() && "html" !in type) throw ServiceException("This link isn't a web page.")
                val bytes = conn.inputStream.use { input -> input.readNBytesCompat(MAX_BYTES) }
                return url to String(bytes, charset(type, bytes))
            } catch (e: IOException) {
                throw ServiceException("Couldn't load the page. Check your connection.")
            } finally {
                conn.disconnect()
            }
        }
        throw ServiceException("The page redirected too many times.")
    }

    private fun charset(contentType: String, bytes: ByteArray): Charset {
        val declared = Regex("""charset=["']?([\w-]+)""").find(contentType)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
                .find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        return declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun parse(html: String, pageUrl: String): JSONObject? {
        val web = WebView(context.applicationContext)
        try {
            web.settings.javaScriptEnabled = true
            web.settings.blockNetworkImage = true
            web.settings.blockNetworkLoads = true
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            // A blank page at the article's address, so its links resolve against it.
            suspendCancellableCoroutine { done ->
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (done.isActive) done.resume(Unit)
                    }
                }
                web.loadDataWithBaseURL(pageUrl, "<!doctype html><html><head></head><body></body></html>", "text/html", "utf-8", null)
            }
            val raw = suspendCancellableCoroutine { done ->
                val call = script + "\nlurkRead(" + JSONObject.quote(html) + ", " + JSONObject.quote(pageUrl) + ");"
                web.evaluateJavascript(call) { if (done.isActive) done.resume(it) }
            }
            return decodeJsResult(raw)
        } finally {
            web.destroy()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val PARSE_TIMEOUT_MS = 20_000L
        const val MAX_REDIRECTS = 6
        const val MAX_BYTES = 4 * 1024 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}

/** Reads up to [limit] bytes; pages past that are cut, which Readability copes with. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}

/** An article in reader view. */
class ReaderScreen(
    /** The link as tapped, before cleaning. */
    val url: String,
    /** The post it came from, for its title while loading. */
    val postTitle: String?,
    private val reader: ArticleReader,
    private val scope: CoroutineScope,
) : Screen {
    var article by mutableStateOf<Article?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val listState = LazyListState()
    private var job: Job? = null

    fun load() {
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                article = reader.read(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Couldn't open this article"
            } finally {
                loading = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Shows [a] as if it had loaded, for screenshots. */
    internal fun showForPreview(a: Article) {
        article = a
    }

    companion object {
        /** Hosts whose links are apps, videos or feeds rather than articles. */
        private val notArticles = listOf(
            "youtube.com", "youtu.be", "twitter.com", "x.com", "instagram.com", "tiktok.com", "twitch.tv",
            "reddit.com", "redd.it", "imgur.com", "gfycat.com", "redgifs.com", "streamable.com", "vimeo.com",
            "spotify.com", "music.apple.com", "soundcloud.com", "bsky.app", "facebook.com", "discord.gg",
            "discord.com", "giphy.com", "tenor.com", "play.google.com", "apps.apple.com", "maps.google.com",
        )
        private val fileTypes = Regex("""\.(jpe?g|png|gif|webp|mp4|webm|mov|m3u8|pdf|zip|apk|mp3)(\?|$)""", RegexOption.IGNORE_CASE)

        /** Whether [url] looks like an article reader view can show. */
        fun looksLikeArticle(url: String): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
            val host = lower.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.").removePrefix("m.")
            if (notArticles.any { host == it || host.endsWith(".$it") }) return false
            if (fileTypes.containsMatchIn(lower.substringBefore('#'))) return false
            // A site's front page isn't an article.
            val path = lower.substringAfter("://").substringAfter('/', "").substringBefore('?').trim('/')
            return path.isNotEmpty()
        }
    }
}
