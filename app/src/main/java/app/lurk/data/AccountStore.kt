package app.lurk.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class Account(
    /** Also the WebView profile name, when profiles are supported. */
    val id: String,
    /** Null until the account signs in (or after it signs out). */
    val username: String? = null,
    /** Cached so the subreddit bar appears instantly on launch; refreshed by sync. */
    val subreddits: List<String> = emptyList(),
    val syncedAt: Long = 0L,
)

/** The list of account slots and which one is active, persisted in SharedPreferences. */
class AccountStore(context: Context, val multiProfile: Boolean) {

    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    var accounts by mutableStateOf(read())
        private set
    var activeId by mutableStateOf("")
        private set

    /** An account just added via "Add account", which should open on the sign-in page. */
    private var pendingLogin: String? = null

    init {
        if (accounts.isEmpty()) accounts = listOf(newAccount())
        activeId = prefs.getString(KEY_ACTIVE, null)
            ?.takeIf { id -> accounts.any { it.id == id } }
            ?: accounts.first().id
        save()
    }

    fun find(id: String): Account? = accounts.firstOrNull { it.id == id }

    /** The WebView profile for an account, or null to use the default profile. */
    fun profileFor(id: String): String? = if (multiProfile) id else null

    fun add(): Account {
        val account = newAccount()
        accounts = accounts + account
        save()
        return account
    }

    fun requestLogin(id: String) {
        pendingLogin = id
    }

    fun consumePendingLogin(id: String): Boolean =
        (pendingLogin == id).also { if (it) pendingLogin = null }

    fun update(id: String, change: (Account) -> Account) {
        accounts = accounts.map { if (it.id == id) change(it) else it }
        save()
    }

    fun setActive(id: String) {
        if (find(id) == null) return
        activeId = id
        save()
    }

    fun remove(id: String) {
        accounts = accounts.filterNot { it.id == id }
        if (accounts.isEmpty()) accounts = listOf(newAccount())
        if (find(activeId) == null) activeId = accounts.first().id
        save()
    }

    private fun newAccount() = Account(
        id = if (multiProfile) "acct_" + UUID.randomUUID().toString().take(8) else "default",
    )

    private fun read(): List<Account> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val subs = o.optJSONArray("subreddits") ?: JSONArray()
                Account(
                    id = o.getString("id"),
                    username = if (o.isNull("username")) null else o.getString("username"),
                    subreddits = (0 until subs.length()).map { subs.getString(it) },
                    syncedAt = o.optLong("syncedAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("username", a.username ?: JSONObject.NULL)
                    .put("subreddits", JSONArray(a.subreddits))
                    .put("syncedAt", a.syncedAt),
            )
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).putString(KEY_ACTIVE, activeId).apply()
    }

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_ACTIVE = "active"
    }
}
