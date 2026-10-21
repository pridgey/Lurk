package app.lurk.web

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.lurk.data.DebugLog
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * A response to a request the engine made with the account's cookies.
 * status 0 = network error; [url] is where redirects ended up.
 */
class HttpResult(val status: Int, val body: String, val url: String)

/**
 * The hidden "engine": a WebView the user never sees (unless Engine debug is open)
 * that loads Reddit pages and pulls data out of them for the native UI.
 * It also makes requests (fetch() with the account's cookies, from an old Reddit
 * page) and hands the responses back through a JavaScript bridge.
 * All calls run on the main thread and one at a time, with a gap between requests.
 */
class RedditEngine(context: Context, profile: String?, scripts: Scripts, private val log: DebugLog) {

    val webView: WebView = WebSetup.create(context, profile, scripts)

    private val mutex = Mutex()
    private var pageWaiter: CompletableDeferred<String>? = null
    private val pending = mutableMapOf<String, CompletableDeferred<HttpResult>>()
    private val main = Handler(Looper.getMainLooper())
    private var lastRequestAt = 0L

    /** Receives fetch() results from the page. Called on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun deliver(id: String, status: Int, url: String, body: String) {
            main.post { pending.remove(id)?.complete(HttpResult(status, body, url)) }
        }
    }

    init {
        webView.addJavascriptInterface(Bridge(), "LurkEngine")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                pageWaiter?.complete(url.orEmpty())
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    pageWaiter?.completeExceptionally(
                        IOException("${error.errorCode}: ${error.description}"),
                    )
                }
            }
        }
    }

    /** Logs which cookies the account has for Reddit (names only) and whether it's signed in. */
    fun logCookies() {
        val names = WebProfiles.cookiesOf(webView).getCookie(RedditLinks.OLD_ORIGIN).orEmpty()
            .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        note("Cookies: ${names.joinToString().ifEmpty { "none" }}")
        note("Login cookie (reddit_session): ${if ("reddit_session" in names) "present" else "missing"}")
    }

    /**
     * Requests [url] (on a Reddit origin) with the account's cookies. [form] makes it a
     * form POST. Requests are spaced out so the engine stays at a human pace.
     */
    suspend fun request(url: String, form: Map<String, String>? = null): HttpResult = mutex.withLock {
        // fetch() runs from a page on the same origin, so cookies go along and CORS allows reading.
        val origin = originOf(url)
        if (originOf(webView.url) != origin) load("$origin/robots.txt")

        val wait = lastRequestAt + REQUEST_GAP_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        lastRequestAt = SystemClock.elapsedRealtime()

        val id = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<HttpResult>()
        pending[id] = waiter
        val body = form?.entries?.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
        val script = """
            (function () {
              var opts = { method: ${JSONObject.quote(if (form == null) "GET" else "POST")}, credentials: 'include' };
              var body = ${if (body == null) "null" else JSONObject.quote(body)};
              if (body !== null) {
                opts.body = body;
                opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
              }
              fetch(${JSONObject.quote(url)}, opts)
                .then(function (r) { return r.text().then(function (t) { LurkEngine.deliver(${JSONObject.quote(id)}, r.status, r.url, t); }); })
                .catch(function (e) { LurkEngine.deliver(${JSONObject.quote(id)}, 0, '', String(e)); });
            })();
        """.trimIndent()

        note((if (form == null) "GET " else "POST ") + url.removePrefix("https://").substringBefore('?'))
        webView.evaluateJavascript(script, null)
        try {
            val result = withTimeout(LOAD_TIMEOUT_MS) { waiter.await() }
            if (result.status != 200) note("  -> HTTP ${result.status}")
            log.lastResult = result.body.take(4_000)
            result
        } finally {
            pending.remove(id)
        }
    }

    private fun originOf(url: String?): String? =
        url?.let { Uri.parse(it) }?.let { if (it.host == null) null else "${it.scheme}://${it.host}" }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** Loads [url] and waits for it to finish. Returns the URL it ended up on. */
    private suspend fun load(url: String): String {
        val waiter = CompletableDeferred<String>()
        pageWaiter = waiter
        note("Loading $url")
        webView.loadUrl(url)
        try {
            withTimeout(LOAD_TIMEOUT_MS) { waiter.await() }
        } finally {
            if (pageWaiter === waiter) pageWaiter = null
        }
        delay(SETTLE_MS) // let late scripts and client-side redirects finish
        return webView.url.orEmpty()
    }

    fun note(message: String) = log.note(message)

    fun destroy() {
        pageWaiter?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        webView.stopLoading()
        webView.detached()
        webView.destroy()
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 25_000L
        const val SETTLE_MS = 400L
        const val REQUEST_GAP_MS = 800L
    }
}
