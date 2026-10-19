package app.lurk.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
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
 * its visible browser, its hidden engine, and keeping the subscription list in sync.
 * Created when an account becomes active and destroyed when you switch away.
 */
class AccountSession(
    context: Context,
    private val store: AccountStore,
    val accountId: String,
    scripts: Scripts,
    private val onMessage: (String) -> Unit,
    onExternal: (Uri) -> Unit,
) {
    private val scope = MainScope()
    private val profile = store.profileFor(accountId)

    val engine = RedditEngine(context, profile, scripts)
    val browser = BrowserController(context, profile, scripts, onExternal) { user ->
        scope.launch { onPageUser(user) }
    }

    var syncing by mutableStateOf(false)
        private set

    private var seenPageUser = false
    private var lastPageUser: String? = null

    val account: Account? get() = store.find(accountId)

    fun start(signInFirst: Boolean) {
        browser.open(if (signInFirst) Links.LOGIN else Links.HOME)
        if (!signInFirst) requestSync()
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
            browser.open(Links.HOME)
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

            store.update(accountId) {
                it.copy(
                    username = user,
                    subreddits = if (user != null) snap.subreddits else emptyList(),
                    syncedAt = System.currentTimeMillis(),
                )
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
