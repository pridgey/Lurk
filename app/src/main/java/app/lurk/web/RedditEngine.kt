package app.lurk.web

import android.content.Context
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
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class SessionSnapshot(
    val username: String?,
    val subreddits: List<String>,
    /** How many of the extractor's selectors found anything. 0 while signed in means it broke. */
    val selectorsMatched: Int,
)

/**
 * The hidden "engine": a WebView the user never sees (unless Engine debug is open)
 * that loads Reddit pages and pulls data out of them for the native UI.
 * All calls run on the main thread and one at a time.
 */
class RedditEngine(context: Context, profile: String?, private val scripts: Scripts) {

    val webView: WebView = WebSetup.create(context, profile, scripts)

    private val mutex = Mutex()
    private var pageWaiter: CompletableDeferred<String>? = null

    /** Recent activity, newest last; shown in Engine debug. */
    val log = mutableStateListOf<String>()

    /** The last raw extraction result, pretty-printed; shown in Engine debug. */
    var lastResult by mutableStateOf<String?>(null)
        private set

    init {
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
                if (username == null) {
                    note("Not signed in (landed on $landed)")
                    break
                }
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
    }
}
