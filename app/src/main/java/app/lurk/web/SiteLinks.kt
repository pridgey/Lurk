package app.lurk.web

import android.net.Uri
import app.lurk.data.Post
import app.lurk.data.Sort

/** Pages the app can show natively instead of in the web view. */
sealed interface Route {
    data class Feed(val community: String?, val sort: Sort? = null) : Route
    data class Thread(val ref: String) : Route
}

sealed interface Decision {
    /** Let the WebView load it. */
    data object Stay : Decision
    /** Load this equivalent instead (e.g. the old Reddit version). */
    data class Rewrite(val uri: Uri) : Decision
    /** Open outside the app (Custom Tab, mail app, ...). */
    data object External : Decision
    /** Ignore it, e.g. intent:// links that try to launch the official app. */
    data object Block : Decision
}

/** What the user is looking at on the web, for the title bar and the selected chip. */
data class Location(
    val label: String,
    /** Lowercase community name, if it's one's page. */
    val community: String? = null,
    val isHome: Boolean = false,
)

/** One site's URL rules: its web pages, which links open natively, and what stays in the web view. */
interface SiteLinks {
    val home: String
    /** Null if the site has no inbox page. */
    val inbox: String?
    /** The site's own page for browsing communities. */
    val communities: String
    fun user(name: String): String
    fun feed(community: String?, sort: Sort?): String
    /** The web page for a thread ref. */
    fun thread(ref: String): String
    /** The link to share for a post. */
    fun share(post: Post): String
    /** Resolves links found in post and comment bodies, which may be relative. */
    fun resolve(raw: String): Uri
    /** The native screen for [uri], or null if it has to be shown on the web. */
    fun route(uri: Uri): Route?
    /** What the visible web view does with a link. */
    fun decide(uri: Uri): Decision
    fun locate(url: String?): Location
    /** A link to the site itself, so a video at it can only play on the post's own page. */
    fun isSiteHost(host: String): Boolean
}
