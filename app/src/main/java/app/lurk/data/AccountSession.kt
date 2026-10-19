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
) {
    private val scope = MainScope()
    private val profile = store.profileFor(accountId)

    val engine = RedditEngine(context, profile, scripts)
    val api = RedditApi(engine)
    val actions = Actions(api, scope, signedIn = { account?.username != null }, onMessage = onMessage)
    val browser = BrowserController(
        context, profile, scripts, onExternal,
        onNative = { uri -> takeOverFromWeb(uri) },
        onPageUser = { user -> scope.launch { onPageUser(user) } },
    )

    /** The back stack; the last entry is on screen. */
    val stack = mutableStateListOf<Screen>()
    val current: Screen get() = stack.last()

    /** An image open in the full-screen viewer. */
    var viewingImage by mutableStateOf<String?>(null)

    var syncing by mutableStateOf(false)
        private set

    private var seenPageUser = false
    private var lastPageUser: String? = null

    val account: Account? get() = store.find(accountId)

    fun start(signInFirst: Boolean) {
        stack += if (signInFirst) WebScreen(Links.LOGIN) else feed(null)
        if (!signInFirst) requestSync()
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
     * The web view followed a link to something with a native screen. Coming from
     * sign-in (not on old Reddit yet), the web page is replaced; otherwise the native
     * screen goes on top so Back returns to the web page.
     */
    private fun takeOverFromWeb(uri: Uri): Boolean {
        val route = Links.route(uri) ?: return false
        if (current !is WebScreen) return false
        val screen = screenFor(route)
        if (!Links.isOldReddit(browser.url)) {
            stack.removeAt(stack.lastIndex)
            // Most likely just signed in; old Reddit never loaded to report it, so check.
            requestSync()
        }
        push(screen)
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
    private suspend fun onPageUser(user: String?) {
        if (seenPageUser && user.equals(lastPageUser, ignoreCase = true)) return
        seenPageUser = true
        lastPageUser = user
        if (!user.equals(account?.username, ignoreCase = true)) sync()
    }

    private suspend fun sync() {
        if (syncing) return
        syncing = true
        try {
            val snap = engine.fetchSession()
            val user = snap.username

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
        } finally {
            syncing = false
        }
    }

    fun destroy() {
        scope.cancel()
        browser.destroy()
        engine.destroy()
    }
}
