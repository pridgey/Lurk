package app.lurk.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
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
        }
        lookLikeChrome(webView.settings)
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        // Apply the skin before the page paints, so there's no flash of unstyled old Reddit.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, scripts.skin, setOf(RedditLinks.OLD_ORIGIN))
        }
        return webView
    }
}

/**
 * Makes requests look like the phone's Chrome rather than an embedded WebView, which
 * Reddit blocks ("You've been blocked by network security"): the user agent string,
 * the client hints that go with it (Sec-CH-UA headers and navigator.userAgentData,
 * which otherwise say "Android WebView"), and no X-Requested-With header naming the app.
 */
@SuppressLint("RequiresFeature")
fun lookLikeChrome(settings: WebSettings) {
    settings.userAgentString = settings.userAgentString
        .replace("; wv", "")
        .replace(" Version/4.0", "")
    if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
        val current = WebSettingsCompat.getUserAgentMetadata(settings)
        val full = current.fullVersion
            ?: Regex("""Chrome/([\d.]+)""").find(settings.userAgentString)?.groupValues?.get(1)
        val major = full?.substringBefore('.')
        if (full != null && major != null) {
            fun brand(name: String, version: String, fullVersion: String) =
                UserAgentMetadata.BrandVersion.Builder()
                    .setBrand(name).setMajorVersion(version).setFullVersion(fullVersion).build()
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder(current)
                    .setBrandVersionList(
                        listOf(
                            brand("Google Chrome", major, full),
                            brand("Chromium", major, full),
                            brand("Not_A Brand", "24", "24.0.0.0"),
                        ),
                    )
                    .setFullVersion(full)
                    .build(),
            )
        }
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
    }
}

/** Removes a view from its current parent so Compose can attach it somewhere new. */
fun <T : View> T.detached(): T = apply { (parent as? ViewGroup)?.removeView(this) }
