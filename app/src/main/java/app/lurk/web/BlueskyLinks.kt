package app.lurk.web

import android.net.Uri
import app.lurk.data.BlueskyService
import app.lurk.data.Post
import app.lurk.data.Sort

/**
 * Bluesky's URL rules. Its web app is bsky.app; links there to posts, feeds and lists
 * open natively, as at:// URIs (with whatever handle or DID the link has, which the
 * service resolves). Other bsky.app pages stay in the web view.
 */
class BlueskyLinks : SiteLinks {
    override val home: String get() = "$ORIGIN/"
    override val inbox: String get() = "$ORIGIN/notifications"
    override val communities: String get() = "$ORIGIN/feeds"
    override fun user(name: String) = "$ORIGIN/profile/$name"

    override fun feed(community: String?, sort: Sort?): String {
        if (community == null) return home
        val (actor, rkey) = parts(community) ?: return home
        return if (BlueskyService.LIST in community) "$ORIGIN/profile/$actor/lists/$rkey" else "$ORIGIN/profile/$actor/feed/$rkey"
    }

    override fun thread(ref: String): String = webPost(ref)

    override fun share(post: Post): String = post.shareUrl ?: webPost(post.threadRef)

    override fun resolve(raw: String): Uri {
        val uri = Uri.parse(raw.trim())
        if (uri.scheme != null) return uri
        return Uri.parse(ORIGIN + if (raw.startsWith("/")) raw.trim() else "/" + raw.trim())
    }

    override fun route(uri: Uri): Route? {
        if (uri.scheme == "at") {
            val path = uri.toString()
            return when {
                "/app.bsky.feed.post/" in path -> Route.Thread(path)
                BlueskyService.GENERATOR in path || BlueskyService.LIST in path -> Route.Feed(path)
                else -> null
            }
        }
        if (!isSiteHost(uri.host.orEmpty())) return null
        val seg = uri.pathSegments
        return when {
            seg.isEmpty() -> Route.Feed(null)
            seg.size == 4 && seg[0] == "profile" && seg[2] == "post" -> Route.Thread("at://${seg[1]}/app.bsky.feed.post/${seg[3]}")
            seg.size == 4 && seg[0] == "profile" && seg[2] == "feed" -> Route.Feed("at://${seg[1]}${BlueskyService.GENERATOR}${seg[3]}")
            seg.size == 4 && seg[0] == "profile" && seg[2] == "lists" -> Route.Feed("at://${seg[1]}${BlueskyService.LIST}${seg[3]}")
            else -> null
        }
    }

    override fun decide(uri: Uri): Decision {
        Links.schemeDecision(uri)?.let { return it }
        return if (isSiteHost(uri.host.orEmpty())) Decision.Stay else Decision.External
    }

    override fun locate(url: String?): Location {
        if (url == null) return Location("Lurk")
        val uri = Uri.parse(url)
        if (!isSiteHost(uri.host.orEmpty())) return Location(uri.host.orEmpty().removePrefix("www."))
        val seg = uri.pathSegments
        return when {
            seg.isEmpty() -> Location("Home", isHome = true)
            seg[0] == "profile" && seg.size == 2 -> Location("@${seg[1]}")
            seg[0] == "notifications" -> Location("Notifications")
            seg[0] == "search" -> Location("Search")
            seg[0] == "feeds" -> Location("Feeds")
            seg[0] == "hashtag" && seg.size >= 2 -> Location("#${seg[1]}")
            seg[0] == "settings" -> Location("Settings")
            else -> Location("Bluesky")
        }
    }

    override fun isSiteHost(host: String): Boolean = host.lowercase().removePrefix("www.") == "bsky.app"

    companion object {
        private const val ORIGIN = "https://bsky.app"

        /** A post's at:// URI as its bsky.app page. */
        fun webPost(uri: String): String {
            val (actor, rkey) = parts(uri) ?: return uri
            return "$ORIGIN/profile/$actor/post/$rkey"
        }

        /** "at://did:plc:x/collection/rkey" to (did:plc:x, rkey). */
        private fun parts(uri: String): Pair<String, String>? {
            val seg = uri.removePrefix("at://").split('/')
            return if (seg.size == 3) seg[0] to seg[2] else null
        }
    }
}
