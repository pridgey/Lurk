package app.lurk.web

import android.content.Context
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The visible WebView, used for pages without a native screen (sign-in, inbox,
 * user pages, "View on web"). Old Reddit pages get the Lurk skin.
 */
class BrowserController(
    context: Context,
    profile: String?,
    private val scripts: Scripts,
    /** The active account's site: which links stay here, which open natively. */
    private val links: SiteLinks,
    private val onExternal: (Uri) -> Unit,
    /** Offered links the app can show natively; returns true if it took over. */
    private val onNative: (Uri) -> Boolean,
    /**
     * Called after each old Reddit page loads with the username the page shows (null if
     * none) and whether the page says someone is signed in.
     */
    private val onPageUser: (username: String?, loggedIn: Boolean) -> Unit,
    /** Called after every page load, on any host. */
    private val onPageLoaded: () -> Unit,
) {
    val webView: WebView = WebSetup.create(context, profile, scripts)

    var url by mutableStateOf<String?>(null)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var canGoBack by mutableStateOf(false)
        private set

    /** Set by [openFresh]: drop the history once the new page has loaded. */
    private var clearHistory = false

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val decision = links.decide(request.url)
                // Links the user (or a page script) follows to feeds and threads go native.
                // Server redirects don't, so "View on web" stays on the web.
                val target = when (decision) {
                    is Decision.Rewrite -> decision.uri
                    Decision.Stay -> request.url
                    else -> null
                }
                if (!request.isRedirect && target != null && links.route(target) != null && onNative(target)) {
                    return true
                }
                return when (decision) {
                    Decision.Stay -> false
                    is Decision.Rewrite -> {
                        view.loadUrl(decision.uri.toString())
                        true
                    }
                    Decision.External -> {
                        onExternal(request.url)
                        true
                    }
                    Decision.Block -> true
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (clearHistory) {
                    clearHistory = false
                    view.clearHistory()
                    canGoBack = false
                }
                onPageLoaded()
                // Old Reddit's login page is new-style Reddit: no skin, and nobody to report yet.
                if (!RedditLinks.isOldReddit(url) || RedditLinks.isSignInPage(url)) return
                // Fallback for WebViews without document-start scripts; a no-op otherwise.
                view.evaluateJavascript(scripts.skin, null)
                view.evaluateJavascript(scripts.whoami) { raw ->
                    val result = decodeJsResult(raw) ?: return@evaluateJavascript
                    onPageUser(result.optNullableString("username"), result.optBoolean("loggedIn"))
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                this@BrowserController.url = url
                canGoBack = view.canGoBack()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
            }
        }
    }

    fun open(url: String) = webView.loadUrl(url)

    /** Opens [url] as the start of a new history, so Back leaves the web view. */
    fun openFresh(url: String) {
        clearHistory = true
        this.url = url
        webView.loadUrl(url)
    }
    fun reload() = webView.reload()
    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun destroy() {
        webView.stopLoading()
        webView.detached()
        webView.destroy()
    }
}
