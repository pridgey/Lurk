package app.lurk.web

import android.net.Uri

/** URL rules: what stays in the app, what gets rewritten to old Reddit, what opens outside. */
object Links {
    const val OLD_HOST = "old.reddit.com"
    const val OLD_ORIGIN = "https://old.reddit.com"
    const val HOME = "https://old.reddit.com/"
    const val INBOX = "https://old.reddit.com/message/inbox/"
    /**
     * Sign in on old Reddit's own login page. Old Reddit sends signed-out visitors there
     * (/login?reason=lor2), and a sign-in on www.reddit.com doesn't carry over to it.
     */
    const val LOGIN = "https://old.reddit.com/login/?dest=https%3A%2F%2Fold.reddit.com%2F"

    fun subreddit(name: String) = "https://old.reddit.com/r/$name/"

    sealed interface Decision {
        /** Let the WebView load it. */
        data object Stay : Decision
        /** Load this old-Reddit equivalent instead. */
        data class Rewrite(val uri: Uri) : Decision
        /** Open outside the app (Custom Tab, mail app, ...). */
        data object External : Decision
        /** Ignore it, e.g. intent:// links that try to launch the official app. */
        data object Block : Decision
    }

    private val newRedditHosts = setOf(
        "reddit.com", "www.reddit.com", "np.reddit.com", "m.reddit.com",
        "new.reddit.com", "i.reddit.com", "amp.reddit.com",
    )

    /** Pages that only exist on new Reddit (mainly sign-in), so they're not rewritten. */
    private val newRedditOnly = listOf(
        "/login", "/register", "/account", "/password", "/settings", "/verification",
        "/media", "/svc/", "/api/", "/chat", "/notifications", "/poll/",
    )

    /** Share links (/r/sub/s/abc) only resolve on new Reddit; it redirects to a normal link. */
    private val shareLink = Regex("^/r/[^/]+/s/[^/]+")

    private val handOffSchemes = setOf("mailto", "tel", "sms", "geo", "market")
    private val inPageSchemes = setOf("about", "data", "blob", "javascript")

    fun isRedditHost(host: String): Boolean =
        host == "reddit.com" || host.endsWith(".reddit.com") ||
            host == "redd.it" || host.endsWith(".redd.it") ||
            host.endsWith(".redditmedia.com") || host.endsWith(".redditstatic.com")

    fun isOldReddit(url: String?): Boolean =
        url != null && Uri.parse(url).host.equals(OLD_HOST, ignoreCase = true)

    private val signInPaths = listOf("/login", "/register", "/account")

    /** Reddit's (new-style) sign-in pages, on any Reddit host, old.reddit.com included. */
    fun isSignInPage(url: String?): Boolean {
        val path = url?.let { Uri.parse(it).path } ?: return false
        return signInPaths.any { path.startsWith(it) }
    }

    fun decide(uri: Uri): Decision {
        val scheme = uri.scheme?.lowercase() ?: return Decision.Stay
        if (scheme != "http" && scheme != "https") {
            return when (scheme) {
                in handOffSchemes -> Decision.External
                in inPageSchemes -> Decision.Stay
                else -> Decision.Block
            }
        }
        val host = uri.host?.lowercase() ?: return Decision.Stay
        if (!isRedditHost(host)) return Decision.External
        if (host !in newRedditHosts) return Decision.Stay

        val path = uri.path ?: "/"
        if (newRedditOnly.any { path.startsWith(it) } || shareLink.containsMatchIn(path)) {
            return Decision.Stay
        }
        return Decision.Rewrite(uri.buildUpon().scheme("https").authority(OLD_HOST).build())
    }

    /** Pages the app can show natively instead of in the web view. */
    sealed interface Route {
        data class Feed(val subreddit: String?, val sort: String? = null, val time: String? = null) : Route
        data class Thread(val permalink: String) : Route
    }

    private val feedSorts = setOf("hot", "new", "rising", "top", "controversial", "best")

    /** Resolves relative links (as found in post and comment bodies) against old Reddit. */
    fun resolve(raw: String): Uri {
        val uri = Uri.parse(raw.trim())
        if (uri.scheme != null) return uri
        val path = if (raw.startsWith("/")) raw.trim() else "/" + raw.trim()
        return Uri.parse(OLD_ORIGIN + path)
    }

    /** The native screen for [uri], or null if it has to be shown on the web. */
    fun route(uri: Uri): Route? {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val seg = uri.pathSegments

        if (host == "redd.it" && seg.size == 1) return Route.Thread("/comments/${seg[0]}/")
        if (host != OLD_HOST && host !in newRedditHosts) return null

        return when {
            seg.isEmpty() -> Route.Feed(null)
            seg.size == 1 && seg[0] in feedSorts -> Route.Feed(null, seg[0], uri.getQueryParameter("t"))
            seg[0] == "comments" && seg.size >= 2 -> Route.Thread(uri.path!!)
            seg[0] != "r" || seg.size < 2 -> null
            seg.size == 2 -> Route.Feed(seg[1])
            seg.size == 3 && seg[2] in feedSorts -> Route.Feed(seg[1], seg[2], uri.getQueryParameter("t"))
            seg[2] == "comments" && seg.size >= 4 -> Route.Thread(uri.path!!)
            else -> null
        }
    }

    private val imageHosts = setOf("i.redd.it", "preview.redd.it", "i.imgur.com")
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")

    /** Direct links to an image file, which open in the image viewer. */
    fun isImage(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase() ?: return false
        return host in imageHosts && imageExtensions.any { path.endsWith(it) }
    }

    /** What the user is looking at, for the title bar and the selected chip. */
    data class Location(
        val label: String,
        val subreddit: String? = null,
        val isHome: Boolean = false,
    )

    private val homeSorts = setOf("hot", "new", "rising", "controversial", "top", "best")

    fun locate(url: String?): Location {
        if (url == null) return Location("Lurk")
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase().orEmpty()
        val seg = uri.pathSegments

        if (isSignInPage(url)) return Location("Sign in")
        if (host != OLD_HOST) return Location(host.removePrefix("www."))
        return when {
            seg.isEmpty() || seg[0] in homeSorts -> Location("Home", isHome = true)
            seg[0] == "r" && seg.size >= 2 -> Location("r/${seg[1]}", subreddit = seg[1].lowercase())
            (seg[0] == "user" || seg[0] == "u") && seg.size >= 2 -> Location("u/${seg[1]}")
            seg[0] == "message" -> Location("Inbox")
            seg[0] == "search" -> Location("Search")
            else -> Location(seg.joinToString("/"))
        }
    }
}
