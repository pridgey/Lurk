package app.lurk.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * A response to a request the engine made with the account's cookies.
 * status 0 = network error; [url] is where redirects ended up.
 */
class HttpResult(val status: Int, val body: String, val url: String)

data class SessionSnapshot(
    val username: String?,
    val subreddits: List<String>,
    /** How many of the extractor's selectors found anything. 0 while signed in means it broke. */
    val selectorsMatched: Int,
)

/**
 * The hidden "engine": a WebView the user never sees (unless Engine debug is open)
 * that loads Reddit pages and pulls data out of them for the native UI.
 * It also makes requests (fetch() with the account's cookies, from an old Reddit
 * page) and hands the responses back through a JavaScript bridge.
 * All calls run on the main thread and one at a time, with a gap between requests.
 */
class RedditEngine(context: Context, profile: String?, private val scripts: Scripts) {

    val webView: WebView = WebSetup.create(context, profile, scripts)

    private val mutex = Mutex()
    private var pageWaiter: CompletableDeferred<String>? = null
    private val pending = mutableMapOf<String, CompletableDeferred<HttpResult>>()
    private val main = Handler(Looper.getMainLooper())
    private var lastRequestAt = 0L

    /** Recent activity, newest last; shown in Engine debug. */
    val log = mutableStateListOf<String>()

    /** The last raw extraction result, pretty-printed; shown in Engine debug. */
    var lastResult by mutableStateOf<String?>(null)
        private set

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

    suspend fun fetchSession(): SessionSnapshot = mutex.withLock { doFetchSession() }

    private suspend fun doFetchSession(): SessionSnapshot {
        note("Sync started")
        // Names only: enough to see whether the login cookie is there, without exposing it.
        val cookieNames = WebProfiles.cookiesOf(webView).getCookie(Links.OLD_ORIGIN).orEmpty()
            .split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        note("Cookies: ${cookieNames.joinToString().ifEmpty { "none" }}")
        note("Login cookie (reddit_session): ${if ("reddit_session" in cookieNames) "present" else "missing"}")
        var next: String? = SUBSCRIPTIONS_URL
        var username: String? = null
        var matched = 0
        val subs = linkedMapOf<String, String>()
        var page = 0

        while (next != null && page < MAX_PAGES) {
            val landed = load(next)
            val result = evalJson(scripts.session)
            lastResult = result.toString(2)

            if (page == 0) {
                username = result.optNullableString("username")
                // Keep reading anyway: the header may have changed while the list still works,
                // and the app double-checks who's signed in another way.
                if (username == null) note("No username in page header (landed on $landed)")
            }

            val found = result.optJSONArray("subreddits") ?: JSONArray()
            for (i in 0 until found.length()) {
                val name = found.getString(i)
                subs.putIfAbsent(name.lowercase(), name)
            }
            matched += result.optJSONArray("matched")?.length() ?: 0
            note("Page ${page + 1}: ${found.length()} subreddits")

            // The sidebar list has everything on one page; no need to page through the rest.
            if (result.optBoolean("complete")) break

            next = result.optNullableString("next")
            page++
            if (next != null) delay(PAGE_DELAY_MS) // human pace between pages
        }

        val list = subs.values.sortedBy { it.lowercase() }
        note("Sync done: ${username?.let { "u/$it" } ?: "signed out"}, ${list.size} subscriptions")
        return SessionSnapshot(username, list, matched)
    }

    /**
     * Requests [url] (on old.reddit.com) with the account's cookies. [form] makes it a
     * form POST. Requests are spaced out so the engine stays at a human pace.
     */
    suspend fun request(url: String, form: Map<String, String>? = null): HttpResult = mutex.withLock {
        if (!Links.isOldReddit(webView.url)) load(ORIGIN_PAGE) // fetch() has to run on old Reddit

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

        note((if (form == null) "GET " else "POST ") + url.removePrefix(Links.OLD_ORIGIN).substringBefore('?'))
        webView.evaluateJavascript(script, null)
        try {
            val result = withTimeout(LOAD_TIMEOUT_MS) { waiter.await() }
            if (result.status != 200) note("  -> HTTP ${result.status}")
            lastResult = result.body.take(4_000)
            result
        } finally {
            pending.remove(id)
        }
    }

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

    private suspend fun evalJson(script: String): JSONObject {
        val raw = suspendCancellableCoroutine<String?> { cont ->
            webView.evaluateJavascript(script) { cont.resume(it) }
        }
        return decodeJsResult(raw)
            ?: throw IllegalStateException("Extractor returned nothing on ${webView.url}")
    }

    private fun note(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.add("$time  $message")
        while (log.size > 60) log.removeAt(0)
    }

    fun destroy() {
        pageWaiter?.cancel()
        pending.values.forEach { it.cancel() }
        pending.clear()
        webView.stopLoading()
        webView.detached()
        webView.destroy()
    }

    private companion object {
        const val SUBSCRIPTIONS_URL = "https://old.reddit.com/subreddits/mine/?limit=100"
        const val MAX_PAGES = 10
        const val LOAD_TIMEOUT_MS = 25_000L
        const val SETTLE_MS = 400L
        const val PAGE_DELAY_MS = 1_500L
        const val REQUEST_GAP_MS = 800L
        /** A small, static old Reddit page to run requests from. */
        const val ORIGIN_PAGE = "https://old.reddit.com/robots.txt"
    }
}
