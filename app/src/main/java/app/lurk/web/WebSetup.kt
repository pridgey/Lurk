package app.lurk.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

object WebSetup {

    /** Creates a WebView on the given account profile, with the skin registered. */
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun create(context: Context, profile: String?, scripts: Scripts): WebView {
        val webView = WebView(context)
        // Profile first: it has to be set before the WebView is otherwise used.
        val cookies = WebProfiles.attach(webView, profile)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Lay pages out at the phone's width instead of old Reddit's desktop width.
            useWideViewPort = false
            loadWithOverviewMode = false
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            // Look like regular Chrome rather than an embedded WebView.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(" Version/4.0", "")
        }
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        // Apply the skin before the page paints, so there's no flash of unstyled old Reddit.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, scripts.skin, setOf(Links.OLD_ORIGIN))
        }
        return webView
    }
}

/** Removes a view from its current parent so Compose can attach it somewhere new. */
fun <T : View> T.detached(): T = apply { (parent as? ViewGroup)?.removeView(this) }
