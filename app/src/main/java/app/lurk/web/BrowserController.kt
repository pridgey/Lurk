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

/** The visible, skinned WebView the user reads Reddit in. */
class BrowserController(
    context: Context,
    profile: String?,
    private val scripts: Scripts,
    private val onExternal: (Uri) -> Unit,
    /** Called after each old Reddit page loads with the username the page shows (null = signed out). */
    private val onPageUser: (String?) -> Unit,
) {
    val webView: WebView = WebSetup.create(context, profile, scripts)

    var url by mutableStateOf<String?>(null)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var canGoBack by mutableStateOf(false)
        private set

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return when (val decision = Links.decide(request.url)) {
                    Links.Decision.Stay -> false
                    is Links.Decision.Rewrite -> {
                        view.loadUrl(decision.uri.toString())
                        true
                    }
                    Links.Decision.External -> {
                        onExternal(request.url)
                        true
                    }
                    Links.Decision.Block -> true
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!Links.isOldReddit(url)) return
                // Fallback for WebViews without document-start scripts; a no-op otherwise.
                view.evaluateJavascript(scripts.skin, null)
                view.evaluateJavascript(scripts.whoami) { raw ->
                    val result = decodeJsResult(raw) ?: return@evaluateJavascript
                    onPageUser(result.optNullableString("username"))
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
