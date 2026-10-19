package app.lurk.web

import android.net.Uri

/** URL rules: what stays in the app, what gets rewritten to old Reddit, what opens outside. */
object Links {
    const val OLD_HOST = "old.reddit.com"
    const val OLD_ORIGIN = "https://old.reddit.com"
    const val HOME = "https://old.reddit.com/"
    const val INBOX = "https://old.reddit.com/message/inbox/"
    const val LOGIN = "https://www.reddit.com/login/?dest=https%3A%2F%2Fold.reddit.com%2F"

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

        if (host != OLD_HOST) {
            return Location(if (seg.firstOrNull() == "login") "Sign in" else host.removePrefix("www."))
        }
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
