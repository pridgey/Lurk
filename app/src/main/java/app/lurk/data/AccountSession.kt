package app.lurk.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lurk.web.BrowserController
import app.lurk.web.Links
import app.lurk.web.RedditLinks
import app.lurk.web.Route
import app.lurk.web.Decision
import app.lurk.web.Scripts
import app.lurk.web.WebProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Everything that belongs to one account while it's the active one: its site's
 * [Service] and the native screens it feeds, its fallback web view, and keeping
 * the subscription list in sync.
 * Created when an account becomes active and destroyed when you switch away.
 */
class AccountSession(
    context: Context,
    private val store: AccountStore,
    val accountId: String,
    val scripts: Scripts,
    private val onMessage: (String) -> Unit,
    private val onExternal: (Uri) -> Unit,
    /** Open on the sign-in page (a just-added account) instead of the front page. */
    signInFirst: Boolean,
) {
    private val scope = MainScope()
    private val profile = store.profileFor(accountId)
    private val seenCommunities = SeenCommunities(context, accountId)

    val service: Service = store.find(accountId).let { a ->
        when (a?.kind ?: ServiceKind.Reddit) {
            ServiceKind.Reddit -> RedditService(context, profile, scripts)
            ServiceKind.Lemmy -> LemmyService(
                instance = a?.instance.orEmpty(),
                token = a?.token,
                onTokenRejected = { onLemmyTokenRejected() },
            )
        }
    }
    val links get() = service.links
    val actions = Actions(service, scope, signedIn = { account?.username != null }, onMessage = onMessage)
    val browser = BrowserController(
        context, profile, scripts, service.links, onExternal,
        onNative = { uri -> takeOverFromWeb(uri) },
        onPageUser = { user, loggedIn -> scope.launch { onPageUser(user, loggedIn) } },
        onPageLoaded = { onWebPageLoaded() },
    )

    /** The back stack; the last entry is on screen. Never empty: the UI reads it on first draw. */
    val stack = mutableStateListOf<Screen>(
        (service.signIn as? SignIn.Web)?.takeIf { signInFirst && !inChrome }?.let { WebScreen(it.url) } ?: feed(null),
    )
    val current: Screen get() = stack.last()

    private val syncOnStart = !signInFirst

    /** An image open in the full-screen viewer. */
    var viewingImage by mutableStateOf<String?>(null)

    /** A smaller copy of [viewingImage] already on screen, shown until the full one loads. */
    var viewingPreview by mutableStateOf<String?>(null)

    /** A post whose video is open in the full-screen player. */
    var playingVideo by mutableStateOf<Post?>(null)

    /** Where [playingVideo] starts, when it carries on from the in-list player. */
    var playingVideoStartMs = 0L

    /** A linked video file open in the full-screen player. */
    var playingFile by mutableStateOf<VideoStream?>(null)

    /** A video page open in the video sheet. */
    var sheetUrl by mutableStateOf<String?>(null)

    /** Lurk's own sign-in form is open (for sites that use one). */
    var signingIn by mutableStateOf(false)

    /**
     * Plays a post's video here: natively if the site hosts a copy, else in its site's
     * embedded player; failing both, in the video sheet. [startMs] picks up a native video mid-way.
     */
    fun openVideo(post: Post, startMs: Long = 0) {
        val media = post.media as? PostMedia.Video ?: return
        when {
            media.stream != null || media.embed != null -> {
                playingVideoStartMs = startMs
                playingVideo = post
            }
            links.isSiteHost(Uri.parse(post.url).host.orEmpty()) -> openWeb(links.thread(post.threadRef))
            else -> sheetUrl = post.url // no player to embed: the page, trimmed to its video
        }
    }

    var syncing by mutableStateOf(false)
        private set

    /** Why the last sync couldn't check the sign-in (offline, rate limited, blocked). Null after one works. */
    var syncProblem by mutableStateOf<String?>(null)
        private set

    /** What the last sync learned about the sign-in, e.g. why it ended. See [Service.authNote]. */
    var authNote by mutableStateOf<String?>(null)
        private set

    private var seenPageUser = false
    private var lastPageUser: String? = null

    val account: Account? get() = store.find(accountId)

    /** Reddit is blocking Lurk's browser, so this account's Reddit opens in Chrome. See [RedditAccess]. */
    val inChrome: Boolean get() = service.kind == ServiceKind.Reddit && RedditAccess.inChrome

    fun start() {
        shareLemmyLogin()
        if (syncOnStart && !inChrome) requestSync()
    }

    /** Opens a Reddit page in a Chrome tab (Chrome mode). */
    fun openInChrome(url: String) = onExternal(Uri.parse(url))

    /** Chrome mode: checks whether Lurk's own browser gets through again. */
    fun retryLurkBrowser() {
        RedditAccess.retry()
        reset(feed(null))
        requestSync()
    }

    /** Lemmy's web pages read the login token from a "jwt" cookie, so web screens are signed in too. */
    private fun shareLemmyLogin() {
        val lemmy = service as? LemmyService ?: return
        val token = lemmy.token ?: return
        WebProfiles.cookiesOf(browser.webView)
            .setCookie("https://${lemmy.instance}", "jwt=$token; Path=/; Secure; SameSite=Lax")
    }

    // --- Navigation ---

    fun feed(community: String?, sort: Sort? = service.feedSorts.firstOrNull()): FeedScreen =
        FeedScreen(community, sort, service, scope).also { it.loadMore() }

    fun thread(ref: String, preview: Post? = null): ThreadScreen =
        ThreadScreen(ref, preview, service, scope).also { it.load() }

    private var discoverScreen: DiscoverScreen? = null

    /** The Discover deck. One per session, so leaving and coming back keeps your place. */
    fun discover(): DiscoverScreen = discoverScreen ?: DiscoverScreen(
        service, scope, seenCommunities,
        leaveOut = { c -> isSubscribed(c.name) || (c.over18 && account?.showNsfw != true) },
        subscribe = { name, on -> setSubscribed(name, on) },
    ).also { discoverScreen = it }

    /** Whether the signed-in account can subscribe to [community]'s feed (not Home, All, ...). */
    fun canSubscribe(community: String?): Boolean =
        community != null && account?.username != null && !service.isMixed(community)

    fun isSubscribed(community: String): Boolean =
        account?.communities?.any { it.equals(community, ignoreCase = true) } == true

    /**
     * Subscribes or unsubscribes, updating the bottom bar at once and putting it back if the
     * site says no. [onDone] gets the new state once the site has it.
     */
    fun setSubscribed(community: String, subscribed: Boolean, onDone: (Boolean) -> Unit = {}) {
        val before = account?.communities ?: return
        fun apply(list: List<String>) = store.update(accountId) { it.copy(communities = list) }
        apply(
            if (subscribed) (before.filterNot { it.equals(community, ignoreCase = true) } + community).sortedBy { it.lowercase() }
            else before.filterNot { it.equals(community, ignoreCase = true) },
        )
        scope.launch {
            try {
                service.setSubscribed(community, subscribed)
                onDone(subscribed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                apply(before)
                onMessage("Couldn't ${if (subscribed) "subscribe" else "unsubscribe"}: ${e.message}")
            }
        }
    }

    fun push(screen: Screen) {
        stack += screen
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Starts over at [screen], as when tapping a community in the bottom bar. */
    fun reset(screen: Screen) {
        stack.clear()
        stack += screen
    }

    fun openWeb(url: String) = push(WebScreen(url))

    /** Opens a link from a native screen: natively if possible, else images, web or outside. */
    fun openLink(raw: String) {
        val uri = links.resolve(raw)
        links.route(uri)?.let {
            push(screenFor(it))
            return
        }
        // Image hosts, and pictrs, the image server Lemmy instances run.
        if (Links.isImage(uri) || (uri.path.orEmpty().startsWith("/pictrs/image/") && Links.isImageFile(uri))) {
            viewingPreview = null
            viewingImage = uri.toString()
            return
        }
        Links.directVideo(uri)?.let { (file, gifLike) ->
            playingFile = VideoStream(hlsUrl = null, mp4Url = file, width = 0, height = 0, durationSec = 0, isGif = gifLike)
            return
        }
        if (Links.isVideoPage(uri)) {
            sheetUrl = uri.toString()
            return
        }
        when (val decision = links.decide(uri)) {
            Decision.Stay -> openWeb(uri.toString())
            is Decision.Rewrite -> openWeb(decision.uri.toString())
            Decision.External -> onExternal(uri)
            Decision.Block -> Unit
        }
    }

    private fun screenFor(route: Route): Screen = when (route) {
        is Route.Feed -> feed(route.community, route.sort ?: service.feedSorts.firstOrNull())
        is Route.Thread -> thread(route.ref)
    }

    /**
     * The web view followed a link to something with a native screen. The native
     * screen goes on top, so Back returns to the web page.
     */
    private fun takeOverFromWeb(uri: Uri): Boolean {
        val route = links.route(uri) ?: return false
        if (current !is WebScreen) return false
        // Leaving sign-in: let old Reddit load. Its page reports who's signed in, and the
        // sync that triggers switches to the native feed.
        if (browser.url == null || (service.kind == ServiceKind.Reddit && RedditLinks.isSignInPage(browser.url))) return false
        push(screenFor(route))
        return true
    }

    fun requestSync() {
        scope.launch { sync() }
    }

    /** Starts signing in: the site's sign-in page, or Lurk's form. */
    fun signIn() {
        if (inChrome) return openInChrome(RedditAccess.Urls.LOGIN) // Chrome's own sign-in
        account?.takeIf { it.expired }?.label?.let { onMessage("Sign in as $it") }
        when (val way = service.signIn) {
            is SignIn.Web -> openWeb(way.url)
            SignIn.Form -> signingIn = true
        }
    }

    /** Lurk's sign-in form succeeded for this (signed-out) account. */
    fun onSignedIn(username: String, token: String) {
        signingIn = false
        (service as? LemmyService)?.token = token
        store.update(accountId) { it.copy(username = username, knownAs = username, token = token) }
        shareLemmyLogin()
        requestSync()
    }

    /** The instance no longer accepts this account's token: it's signed out. */
    private fun onLemmyTokenRejected() {
        store.expire(accountId)
    }

    /** Signs this account out on this device (or removes a signed-out one): wipes its cookies and removes it. */
    fun signOut() {
        val name = account?.label
        (service as? LemmyService)?.let { lemmy -> scope.launch { runCatching { lemmy.logOut() } } }
        if (store.multiProfile) {
            store.remove(accountId) // switches the UI away, which destroys this session
            WebProfiles.wipe(profile)
        } else {
            WebProfiles.wipe(null)
            store.update(accountId) { it.copy(username = null, knownAs = null, communities = emptyList(), token = null) }
            service.forget()
            reset(feed(null))
        }
        if (name != null) onMessage("Signed out of $name")
    }

    /** The login cookie at the last sync triggered by a web page, to sync once per sign-in. */
    private var lastLoginCookie: String? = null

    /**
     * After any web page loads while signed out: a new login cookie means sign-in just
     * finished, even if the page itself (e.g. old Reddit's login wall) doesn't show it.
     */
    private fun onWebPageLoaded() {
        if (service.kind != ServiceKind.Reddit || account?.signedIn == true) return
        val cookie = WebProfiles.loginCookie(browser.webView) ?: return
        if (cookie == lastLoginCookie) return
        lastLoginCookie = cookie
        requestSync()
    }

    /** The page reports who's signed in; resync when that changes (login, logout, switch). */
    private suspend fun onPageUser(user: String?, loggedIn: Boolean) {
        // Just signed in, by the page's say-so or because this is the end of the sign-in
        // page: always check, even if the page header didn't give a username.
        val onSignIn = (current as? WebScreen)?.url == RedditLinks.LOGIN
        if (account?.username == null && (loggedIn || onSignIn)) {
            lastPageUser = user
            seenPageUser = true
            sync()
            return
        }
        if (seenPageUser && user.equals(lastPageUser, ignoreCase = true)) return
        seenPageUser = true
        lastPageUser = user
        if (!user.equals(account?.username, ignoreCase = true)) sync()
    }

    /** Set when a sync is asked for while one runs; that one then runs again. */
    private var resync = false

    private suspend fun sync() {
        if (syncing) {
            resync = true // e.g. sign-in finished mid-sync; the running one may be stale
            return
        }
        syncing = true
        try {
            do {
                resync = false
                syncOnce()
            } while (resync)
        } finally {
            syncing = false
        }
    }

    private suspend fun syncOnce() {
        try {
            service.log.note("Sync started")
            val user = service.whoAmI()
            val communities = if (user != null) service.subscriptions() else null
            service.log.note("Sync done: ${user?.let { service.naming.user(it) } ?: "signed out"}, ${communities?.size ?: 0} subscriptions")
            syncProblem = null
            authNote = service.authNote

            val me = account ?: return
            if (user != null) {
                val existing = store.accounts.firstOrNull {
                    it.id != accountId && it.kind == me.kind && it.instance == me.instance &&
                        (it.username ?: it.knownAs).equals(user, ignoreCase = true)
                }
                if (existing?.signedIn == true) {
                    // Signed into an account that's already added: switch to it, drop this slot.
                    onMessage("${existing.label} is already added, switched to it")
                    store.setActive(existing.id)
                    store.remove(accountId)
                    WebProfiles.wipe(profile)
                    return
                }
                // A signed-out copy of this account: this slot replaces it, keeping its settings.
                if (existing != null) store.absorb(from = existing.id, into = accountId)
            }

            val before = me.username
            if (user == null) {
                if (before != null) {
                    service.log.note("Sign-in ended for ${me.label}")
                    onMessage("${me.label} was signed out. Open the account menu to sign in again.")
                }
                store.expire(accountId) // keeps the name and cached communities
                store.update(accountId) { it.copy(syncedAt = System.currentTimeMillis()) }
            } else {
                store.update(accountId) {
                    it.copy(
                        username = user,
                        knownAs = user,
                        communities = communities.orEmpty(),
                        syncedAt = System.currentTimeMillis(),
                    )
                }
            }
            if (!user.equals(before, ignoreCase = true)) {
                // Signed in, out, or as someone else: what we've loaded belongs to the old state.
                service.forget()
                discoverScreen = null
                if (current !is WebScreen || user != null) reset(feed(null))
            }
        } catch (e: TimeoutCancellationException) {
            syncProblem = "Couldn't reach the site in time. Check your connection."
            onMessage("Sync timed out. Check your connection.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncProblem = e.message ?: "Sync failed."
            onMessage(if (inChrome) "Reddit is blocking Lurk's browser, so Reddit opens in Chrome." else "Sync failed: ${e.message}")
        }
    }

    fun destroy() {
        scope.cancel()
        browser.destroy()
        service.destroy()
    }
}
