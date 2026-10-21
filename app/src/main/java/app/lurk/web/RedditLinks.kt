package app.lurk.web

import android.net.Uri
import app.lurk.data.Post
import app.lurk.data.RedditSorts
import app.lurk.data.Sort

/** Reddit's URL rules: what stays in the app, what gets rewritten to old Reddit, what opens outside. */
object RedditLinks : SiteLinks {
    const val OLD_HOST = "old.reddit.com"
    const val OLD_ORIGIN = "https://old.reddit.com"
    const val WWW_ORIGIN = "https://www.reddit.com"

    /**
     * Where Reddit pages and data come from: old Reddit, or www.reddit.com when old
     * Reddit turns a signed-in account away (it sends it to /login?reason=lor2 even
     * with a valid login). Set by [app.lurk.data.RedditService] for the active account.
     */
    @Volatile
    var webOrigin: String = OLD_ORIGIN

    val usingOld: Boolean get() = webOrigin == OLD_ORIGIN

    override val home: String get() = "$webOrigin/"
    override val inbox: String get() = "$webOrigin/message/inbox/"
    /**
     * Sign in on old Reddit's own login page. Old Reddit sends signed-out visitors there
     * (/login?reason=lor2), and a sign-in on www.reddit.com doesn't carry over to it.
     */
    const val LOGIN = "https://old.reddit.com/login/?dest=https%3A%2F%2Fold.reddit.com%2F"

    fun subreddit(name: String) = "$webOrigin/r/$name/"
    override fun user(name: String) = "$webOrigin/user/$name/"

    override fun feed(community: String?, sort: Sort?): String {
        val base = if (community == null) home else subreddit(community)
        val (path, time) = RedditSorts.split(sort)
        return base + path + "/" + (time?.let { "?t=$it" } ?: "")
    }

    override fun thread(ref: String): String = webOrigin + ref

    override fun share(post: Post): String = WWW_ORIGIN + post.threadRef

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

    fun isRedditHost(host: String): Boolean =
        host == "reddit.com" || host.endsWith(".reddit.com") ||
            host == "redd.it" || host.endsWith(".redd.it") ||
            host.endsWith(".redditmedia.com") || host.endsWith(".redditstatic.com")

    override fun isSiteHost(host: String): Boolean = isRedditHost(host.lowercase())

    fun isOldReddit(url: String?): Boolean =
        url != null && Uri.parse(url).host.equals(OLD_HOST, ignoreCase = true)

    private val signInPaths = listOf("/login", "/register", "/account")

    /** Reddit's (new-style) sign-in pages, on any Reddit host, old.reddit.com included. */
    fun isSignInPage(url: String?): Boolean {
        val path = url?.let { Uri.parse(it).path } ?: return false
        return signInPaths.any { path.startsWith(it) }
    }

    override fun decide(uri: Uri): Decision {
        Links.schemeDecision(uri)?.let { return it }
        val host = uri.host?.lowercase() ?: return Decision.Stay
        if (!isRedditHost(host)) return Decision.External
        val path = uri.path ?: "/"

        if (!usingOld) {
            // Old Reddit is walled off for this account: send its links to www instead.
            if (host != OLD_HOST || isSignInPage(uri.toString())) return Decision.Stay
            return Decision.Rewrite(uri.buildUpon().scheme("https").authority("www.reddit.com").build())
        }
        if (host !in newRedditHosts) return Decision.Stay

        if (newRedditOnly.any { path.startsWith(it) } || shareLink.containsMatchIn(path)) {
            return Decision.Stay
        }
        return Decision.Rewrite(uri.buildUpon().scheme("https").authority(OLD_HOST).build())
    }

    private val feedSorts = setOf("hot", "new", "rising", "top", "controversial", "best")

    /** Resolves relative links (as found in post and comment bodies) against Reddit. */
    override fun resolve(raw: String): Uri {
        val uri = Uri.parse(raw.trim())
        if (uri.scheme != null) return uri
        val path = if (raw.startsWith("/")) raw.trim() else "/" + raw.trim()
        return Uri.parse(webOrigin + path)
    }

    override fun route(uri: Uri): Route? {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val seg = uri.pathSegments

        if (host == "redd.it" && seg.size == 1) return Route.Thread("/comments/${seg[0]}/")
        if (host != OLD_HOST && host !in newRedditHosts) return null

        fun sort(path: String) = RedditSorts.of(path, uri.getQueryParameter("t"))
        return when {
            seg.isEmpty() -> Route.Feed(null)
            seg.size == 1 && seg[0] in feedSorts -> Route.Feed(null, sort(seg[0]))
            seg[0] == "comments" && seg.size >= 2 -> Route.Thread(uri.path!!)
            seg[0] != "r" || seg.size < 2 -> null
            seg.size == 2 -> Route.Feed(seg[1])
            seg.size == 3 && seg[2] in feedSorts -> Route.Feed(seg[1], sort(seg[2]))
            seg[2] == "comments" && seg.size >= 4 -> Route.Thread(uri.path!!)
            else -> null
        }
    }

    private val homeSorts = setOf("hot", "new", "rising", "controversial", "top", "best")

    override fun locate(url: String?): Location {
        if (url == null) return Location("Lurk")
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase().orEmpty()
        val seg = uri.pathSegments

        if (isSignInPage(url)) return Location("Sign in")
        if (host != OLD_HOST && host !in newRedditHosts) return Location(host.removePrefix("www."))
        return when {
            seg.isEmpty() || seg[0] in homeSorts -> Location("Home", isHome = true)
            seg[0] == "r" && seg.size >= 2 -> Location("r/${seg[1]}", community = seg[1].lowercase())
            (seg[0] == "user" || seg[0] == "u") && seg.size >= 2 -> Location("u/${seg[1]}")
            seg[0] == "message" -> Location("Inbox")
            seg[0] == "search" -> Location("Search")
            else -> Location(seg.joinToString("/"))
        }
    }
}
