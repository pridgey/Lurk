package app.lurk.web

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Multi-account support. Each account gets its own WebView *profile*: a separate
 * cookie jar and storage, so several Reddit logins can coexist without logging
 * each other out. Needs a reasonably recent Android System WebView; when it isn't
 * available the app falls back to a single account on the default profile.
 */
object WebProfiles {

    val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /**
     * Puts [webView] on [profile]. Must be called before anything else is done with
     * the WebView. Returns the cookie manager for that profile.
     */
    @SuppressLint("RequiresFeature")
    fun attach(webView: WebView, profile: String?): CookieManager {
        if (profile == null || !supported) return CookieManager.getInstance()
        ProfileStore.getInstance().getOrCreateProfile(profile)
        WebViewCompat.setProfile(webView, profile)
        return WebViewCompat.getProfile(webView).cookieManager
    }

    /** The cookie jar [webView] uses (its profile's, or the default one). */
    @SuppressLint("RequiresFeature")
    fun cookiesOf(webView: WebView): CookieManager =
        if (supported) WebViewCompat.getProfile(webView).cookieManager else CookieManager.getInstance()

    /** The value of Reddit's login cookie (reddit_session) in [webView]'s jar, if any. */
    fun loginCookie(webView: WebView): String? =
        cookiesOf(webView).getCookie(Links.OLD_ORIGIN).orEmpty().split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("reddit_session=") }
            ?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    /**
     * Signs a profile out by clearing its cookies and storage, then deletes it once
     * its WebViews are gone. With no profile support this wipes the default profile.
     */
    @SuppressLint("RequiresFeature")
    fun wipe(profile: String?) {
        if (profile == null || !supported) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            return
        }
        val store = ProfileStore.getInstance()
        store.getProfile(profile)?.let { p ->
            runCatching { p.cookieManager.removeAllCookies(null) }
            runCatching { p.webStorage.deleteAllData() }
        }
        // A profile can't be deleted while a WebView still uses it; give the UI a moment
        // to tear the account's WebViews down. If it still fails, the profile is left
        // behind empty, which is harmless.
        Handler(Looper.getMainLooper()).postDelayed(
            { runCatching { store.deleteProfile(profile) } },
            2_000,
        )
    }
}
