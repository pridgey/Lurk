package app.lurk.data

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lurk.web.SiteLinks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The sites Lurk can read. Each account belongs to one. */
enum class ServiceKind { Reddit, Lemmy }

/** A sort order as a site names it: [key] is sent to the site, [label] is shown. */
data class Sort(val key: String, val label: String)

/** A feed pinned in the bar right after Home, like r/popular. */
data class FixedFeed(val community: String, val label: String)

/** How a site names things on screen. */
interface Naming {
    /** "subreddit", "community". */
    val communityNoun: String
    /** "r/pics", "c/pics". */
    fun community(name: String): String
    /** "u/name", "name@instance". */
    fun user(name: String): String
    /** A short label for the bottom bar, given every subscription so it can stay unambiguous. */
    fun chip(name: String, all: List<String>): String = name
    /** [typed] with any "r/"-style prefix removed. */
    fun clean(typed: String): String
    /** Whether [name] (already cleaned) could be a community to go to. */
    fun isCommunityName(name: String): Boolean
}

sealed interface SignIn {
    /** Sign in on this web page; the session notices when it's done. */
    data class Web(val url: String) : SignIn
    /** Sign in with Lurk's own username and password form. */
    data object Form : SignIn
}

/** Recent activity for Engine debug, newest last. */
class DebugLog {
    val lines = mutableStateListOf<String>()

    /** The start of the last response. */
    var lastResult by mutableStateOf<String?>(null)

    fun note(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.add("$time  $message")
        while (lines.size > 60) lines.removeAt(0)
    }
}

/**
 * One site's data, for one account: what the native screens need from Reddit, Lemmy,
 * and later others (Bluesky). Everything the screens show comes back as the shared
 * models ([Post], [Comment], [More]); ids and refs inside them mean whatever the
 * service that made them needs, and only go back to that service.
 */
interface Service {
    val kind: ServiceKind
    val naming: Naming
    val links: SiteLinks
    /** First is the default. Empty: no sort menu. */
    val feedSorts: List<Sort>
    val commentSorts: List<Sort>
    val fixedFeeds: List<FixedFeed>
    val signIn: SignIn
    /** False where the site (or this instance) has no downvotes. */
    val downvotes: Boolean
    val log: DebugLog
    /** A hidden WebView that does the work, if the service uses one; shown in Engine debug. */
    val engineView: WebView? get() = null

    /** The signed-in username, or null when signed out. */
    suspend fun whoAmI(): String?

    /** Subscribed communities, alphabetical. */
    suspend fun subscriptions(): List<String>

    /** A page of posts: Home ([community] = null), a fixed feed, or a community. */
    suspend fun feed(community: String?, sort: Sort?, cursor: String?): FeedPage

    /** Whether [community]'s feed mixes communities, so each post shows its own. */
    fun isMixed(community: String?): Boolean

    /** A post and its comments. [ref] is a [Post.threadRef], or one from [links]' routes. */
    suspend fun thread(ref: String, sort: Sort?): ThreadData

    /** Loads [more]'s comments, to put in its place. */
    suspend fun moreChildren(post: Post, more: More, sort: Sort?): List<ThreadItem>

    /** The thread to open for a [More] row with no children to load in place. */
    fun continueRef(post: Post?, threadRef: String, more: More): String

    /** dir: 1 up, 0 clear, -1 down. [name] is a [Post.name] or [Comment.name]. */
    suspend fun vote(name: String, dir: Int)

    suspend fun setSaved(name: String, saved: Boolean)

    /** Drops anything cached for the signed-in user, after they change. */
    fun forget() {}

    fun destroy() {}
}

open class ServiceException(message: String) : Exception(message)
