package app.lurk.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lurk.web.BrowserController
import app.lurk.web.Links
import app.lurk.web.RedditEngine
import app.lurk.web.Scripts
import app.lurk.web.WebProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Everything that belongs to one signed-in account while it's the active one:
 * its hidden engine and the native screens it feeds, its fallback web view,
 * and keeping the subscription list in sync.
 * Created when an account becomes active and destroyed when you switch away.
 */
class AccountSession(
    context: Context,
    private val store: AccountStore,
    val accountId: String,
    scripts: Scripts,
    private val onMessage: (String) -> Unit,
    private val onExternal: (Uri) -> Unit,
    /** Open on the sign-in page (a just-added account) instead of the front page. */
    signInFirst: Boolean,
) {
    private val scope = MainScope()
    private val profile = store.profileFor(accountId)

    val engine = RedditEngine(context, profile, scripts)
    val api = RedditApi(engine)
    val actions = Actions(api, scope, signedIn = { account?.username != null }, onMessage = onMessage)
    val browser = BrowserController(
        context, profile, scripts, onExternal,
        onNative = { uri -> takeOverFromWeb(uri) },
        onPageUser = { user, loggedIn -> scope.launch { onPageUser(user, loggedIn) } },
    )

    /** The back stack; the last entry is on screen. Never empty: the UI reads it on first draw. */
    val stack = mutableStateListOf<Screen>(if (signInFirst) WebScreen(Links.LOGIN) else feed(null))
    val current: Screen get() = stack.last()

    private val syncOnStart = !signInFirst

    /** An image open in the full-screen viewer. */
    var viewingImage by mutableStateOf<String?>(null)

    var syncing by mutableStateOf(false)
        private set

    private var seenPageUser = false
    private var lastPageUser: String? = null

    val account: Account? get() = store.find(accountId)

    fun start() {
        if (syncOnStart) requestSync()
    }

    // --- Navigation ---

    fun feed(subreddit: String?, sort: FeedSort = FeedSort.Hot): FeedScreen =
        FeedScreen(subreddit, sort, api, scope).also { it.loadMore() }

    fun thread(permalink: String, preview: Post? = null): ThreadScreen =
        ThreadScreen(permalink, preview, api, scope).also { it.load() }

    fun push(screen: Screen) {
        stack += screen
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Starts over at [screen], as when tapping a subreddit in the bottom bar. */
    fun reset(screen: Screen) {
        stack.clear()
        stack += screen
    }

    fun openWeb(url: String) = push(WebScreen(url))

    /** Opens a link from a native screen: natively if possible, else images, web or outside. */
    fun openLink(raw: String) {
        val uri = Links.resolve(raw)
        Links.route(uri)?.let {
            push(screenFor(it))
            return
        }
        if (Links.isImage(uri)) {
            viewingImage = uri.toString()
            return
        }
        when (val decision = Links.decide(uri)) {
            Links.Decision.Stay -> openWeb(uri.toString())
            is Links.Decision.Rewrite -> openWeb(decision.uri.toString())
            Links.Decision.External -> onExternal(uri)
            Links.Decision.Block -> Unit
        }
    }

    private fun screenFor(route: Links.Route): Screen = when (route) {
        is Links.Route.Feed -> feed(route.subreddit, FeedSort.of(route.sort, route.time))
        is Links.Route.Thread -> thread(route.permalink)
    }

    /**
     * The web view followed a link to something with a native screen. The native
     * screen goes on top, so Back returns to the web page.
     */
    private fun takeOverFromWeb(uri: Uri): Boolean {
        val route = Links.route(uri) ?: return false
        if (current !is WebScreen) return false
        // Leaving sign-in: let old Reddit load. Its page reports who's signed in, and the
        // sync that triggers switches to the native feed.
        if (!Links.isOldReddit(browser.url) || Links.isSignInPage(browser.url)) return false
        push(screenFor(route))
        return true
    }

    fun requestSync() {
        scope.launch { sync() }
    }

    /** Signs this account out on this device: wipes its cookies and removes it. */
    fun signOut() {
        val name = account?.username
        if (store.multiProfile) {
            store.remove(accountId) // switches the UI away, which destroys this session
            WebProfiles.wipe(profile)
        } else {
            WebProfiles.wipe(null)
            store.update(accountId) { it.copy(username = null, subreddits = emptyList()) }
            api.forget()
            reset(feed(null))
        }
        if (name != null) onMessage("Signed out of u/$name")
    }

    /** The page reports who's signed in; resync when that changes (login, logout, switch). */
    private suspend fun onPageUser(user: String?, loggedIn: Boolean) {
        // Just signed in, by the page's say-so or because this is the end of the sign-in
        // page: always check, even if the page header didn't give a username.
        val onSignIn = (current as? WebScreen)?.url == Links.LOGIN
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
            val snap = engine.fetchSession()
            // The page header is one way to tell who's signed in; ask Reddit directly too.
            val user = snap.username ?: api.whoAmI()

            if (user != null) {
                val existing = store.accounts.firstOrNull {
                    it.id != accountId && it.username.equals(user, ignoreCase = true)
                }
                if (existing != null) {
                    // Signed into an account that's already added: switch to it, drop this slot.
                    onMessage("u/$user is already added, switched to it")
                    store.setActive(existing.id)
                    store.remove(accountId)
                    WebProfiles.wipe(profile)
                    return
                }
            }

            val before = account?.username
            store.update(accountId) {
                it.copy(
                    username = user,
                    subreddits = if (user != null) snap.subreddits else emptyList(),
                    syncedAt = System.currentTimeMillis(),
                )
            }
            if (!user.equals(before, ignoreCase = true)) {
                // Signed in, out, or as someone else: what we've loaded belongs to the old state.
                api.forget()
                if (current !is WebScreen || user != null) reset(feed(null))
            }
            if (user != null && snap.selectorsMatched == 0) {
                onMessage("Signed in, but couldn't read your subscriptions. See Engine debug.")
            }
        } catch (e: TimeoutCancellationException) {
            onMessage("Sync timed out. Check your connection.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onMessage("Sync failed: ${e.message}")
        }
    }

    fun destroy() {
        scope.cancel()
        browser.destroy()
        engine.destroy()
    }
}
