package app.lurk.web

import android.net.Uri
import app.lurk.data.LemmyService
import app.lurk.data.Post
import app.lurk.data.Sort

/**
 * A Lemmy instance's URL rules. Its own pages stay in the web view; links to posts,
 * comments and communities, on it or on another instance, open natively.
 */
class LemmyLinks(private val instance: String) : SiteLinks {
    private val origin = "https://$instance"

    override val home: String get() = "$origin/"
    override val inbox: String get() = "$origin/inbox"
    override val communities: String get() = "$origin/communities"
    override fun user(name: String) = "$origin/u/$name"

    override fun feed(community: String?, sort: Sort?): String {
        val sortParam = sort?.let { "sort=${it.key}" }
        return when (community) {
            null -> "$origin/" + (sortParam?.let { "?listingType=Subscribed&$it" } ?: "?listingType=Subscribed")
            LemmyService.LOCAL -> "$origin/?listingType=Local" + (sortParam?.let { "&$it" } ?: "")
            LemmyService.ALL -> "$origin/?listingType=All" + (sortParam?.let { "&$it" } ?: "")
            else -> "$origin/c/$community" + (sortParam?.let { "?$it" } ?: "")
        }
    }

    override fun thread(ref: String): String = when {
        ref.startsWith("post:") -> "$origin/post/${ref.removePrefix("post:")}"
        ref.startsWith("comment:") -> "$origin/comment/${ref.removePrefix("comment:")}"
        else -> ref // a link to another instance
    }

    override fun share(post: Post): String = post.shareUrl ?: thread(post.threadRef)

    override fun resolve(raw: String): Uri {
        val uri = Uri.parse(raw.trim())
        if (uri.scheme != null) return uri
        val path = if (raw.startsWith("/")) raw.trim() else "/" + raw.trim()
        return Uri.parse(origin + path)
    }

    private val number = Regex("\\d+")
    private val communityName = Regex("[A-Za-z0-9_]+(@[A-Za-z0-9.-]+)?")

    override fun route(uri: Uri): Route? {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val seg = uri.pathSegments
        val local = host == instance
        return when {
            local && seg.isEmpty() -> Route.Feed(null)
            seg.size == 2 && seg[0] == "post" && seg[1].matches(number) ->
                Route.Thread(if (local) "post:${seg[1]}" else uri.toString())
            // lemmy-ui links a comment as /post/1/2 or /comment/2.
            seg.size == 3 && seg[0] == "post" && seg[2].matches(number) ->
                Route.Thread(if (local) "comment:${seg[2]}" else uri.toString())
            seg.size == 2 && seg[0] == "comment" && seg[1].matches(number) ->
                Route.Thread(if (local) "comment:${seg[1]}" else uri.toString())
            seg.size == 2 && seg[0] == "c" && seg[1].matches(communityName) -> {
                val name = seg[1]
                Route.Feed(if (local || '@' in name) name else "$name@$host")
            }
            else -> null
        }
    }

    override fun decide(uri: Uri): Decision {
        Links.schemeDecision(uri)?.let { return it }
        val host = uri.host?.lowercase() ?: return Decision.Stay
        return if (host == instance) Decision.Stay else Decision.External
    }

    override fun locate(url: String?): Location {
        if (url == null) return Location("Lurk")
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase().orEmpty()
        if (host != instance) return Location(host.removePrefix("www."))
        val seg = uri.pathSegments
        return when {
            seg.isEmpty() -> Location("Home", isHome = true)
            seg[0] == "c" && seg.size >= 2 -> Location("c/${seg[1]}", community = seg[1].lowercase())
            seg[0] == "u" && seg.size >= 2 -> Location("@${seg[1]}")
            seg[0] == "inbox" -> Location("Inbox")
            seg[0] == "login" -> Location("Sign in")
            seg[0] == "search" -> Location("Search")
            else -> Location(seg.joinToString("/"))
        }
    }

    override fun isSiteHost(host: String): Boolean = host.lowercase() == instance
}
