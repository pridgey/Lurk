package app.lurk.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lurk.web.WebProfiles
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONArray
import org.json.JSONObject

data class Account(
    /** Also the WebView profile name, when profiles are supported. */
    val id: String,
    val kind: ServiceKind = ServiceKind.Reddit,
    /** Who's signed in right now. Null until the account signs in, or once its sign-in ends. */
    val username: String? = null,
    /**
     * Who this account was last signed in as. Kept when the site ends the sign-in (it
     * expired, or the site stopped accepting it), so the account still has a name and
     * can be signed back in. Cleared only by signing out on purpose.
     */
    val knownAs: String? = null,
    /** Cached so the community bar appears instantly on launch; refreshed by sync. */
    val communities: List<String> = emptyList(),
    val syncedAt: Long = 0L,
    /** Set when switching to this account needs a PIN: a salted PBKDF2 hash, never the PIN itself. */
    val pinHash: String? = null,
    val pinSalt: String? = null,
    /** Show NSFW media without a tap to reveal. Spoilers still hide. */
    val showNsfw: Boolean = false,
    /** Lemmy: the instance's host, e.g. "lemmy.world". */
    val instance: String? = null,
    /** Lemmy: the login token (JWT) the instance issued. Bluesky: the access token. Null when signed out. */
    val token: String? = null,
    /** Bluesky: the token that renews [token]. Each renewal replaces both. */
    val refreshToken: String? = null,
) {
    val locked: Boolean get() = pinHash != null

    val signedIn: Boolean get() = username != null

    /** Was signed in, but the site has ended that sign-in. */
    val expired: Boolean get() = username == null && knownAs != null

    /** Where the account lives: "Reddit", "lemmy.world". */
    val siteName: String
        get() = when (kind) {
            ServiceKind.Reddit -> "Reddit"
            ServiceKind.Lemmy -> instance ?: "Lemmy"
            ServiceKind.Bluesky -> "Bluesky"
        }

    /** How the account is named in menus: "u/name", "name@lemmy.world". Kept while [expired]. */
    val label: String?
        get() = (username ?: knownAs)?.let { name ->
            when (kind) {
                ServiceKind.Reddit -> "u/$name"
                ServiceKind.Lemmy -> "$name@$instance"
                ServiceKind.Bluesky -> "@$name"
            }
        }
}

/** The list of account slots and which one is active, persisted in SharedPreferences. */
class AccountStore(context: Context, val multiProfile: Boolean) {

    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    var accounts by mutableStateOf(read())
        private set
    var activeId by mutableStateOf("")
        private set

    /** The PIN-locked account opened since the app was last away for a while. Not saved. */
    var unlockedId by mutableStateOf<String?>(null)
        private set

    /** Where "Use another account" on the lock screen goes back to. */
    private var previousId: String? = null

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

    fun add(kind: ServiceKind = ServiceKind.Reddit, change: (Account) -> Account = { it }): Account {
        val account = change(newAccount().copy(kind = kind))
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
        if (id != activeId) previousId = activeId
        if (id != unlockedId) unlockedId = null // leaving a PIN account locks it again
        activeId = id
        save()
    }

    /** True when [id] has a PIN that hasn't been entered yet. */
    fun needsPin(id: String): Boolean = find(id)?.locked == true && unlockedId != id

    fun unlock(id: String, pin: String): Boolean {
        val a = find(id) ?: return false
        val ok = a.pinHash != null && a.pinSalt != null && hash(pin, a.pinSalt) == a.pinHash
        if (ok) unlockedId = id
        return ok
    }

    /** Asks for the PIN again next time; called after the app has been away a while. */
    fun lockAll() {
        unlockedId = null
    }

    fun setPin(id: String, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }
        update(id) { it.copy(pinHash = hash(pin, salt), pinSalt = salt) }
        unlockedId = id
    }

    fun clearPin(id: String) = update(id) { it.copy(pinHash = null, pinSalt = null) }

    /** The site ended [id]'s sign-in. Keeps its name, settings and cached communities. */
    fun expire(id: String) = update(id) {
        if (it.username == null) it else it.copy(username = null, knownAs = it.username, token = null, refreshToken = null)
    }

    /**
     * [from] is a signed-out copy of the account now signed in as [into]: moves its
     * settings (PIN included; signing in just proved who this is) over, then drops it.
     */
    fun absorb(from: String, into: String) {
        val old = find(from) ?: return
        update(into) { it.copy(pinHash = old.pinHash, pinSalt = old.pinSalt, showNsfw = old.showNsfw) }
        if (old.locked) unlockedId = into
        runCatching { WebProfiles.wipe(profileFor(from)) }
        remove(from)
    }

    /** An account to show instead of a locked one: the one before it, or any without a PIN. */
    fun elsewhere(): String? =
        previousId?.takeIf { it != activeId && find(it)?.locked == false }
            ?: accounts.firstOrNull { !it.locked && it.id != activeId }?.id

    /** Removes an account and its sign-in without opening it (for a forgotten PIN). */
    fun forget(id: String) {
        runCatching { WebProfiles.wipe(profileFor(id)) }
        if (multiProfile) remove(id)
        else update(id) { Account(id = it.id, kind = it.kind, instance = it.instance) }
        if (unlockedId == id) unlockedId = null
    }

    private fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(salt), 20_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
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
                    kind = ServiceKind.entries.firstOrNull { it.name == o.optString("kind") } ?: ServiceKind.Reddit,
                    username = if (o.isNull("username")) null else o.getString("username"),
                    knownAs = o.optString("knownAs").ifBlank { null },
                    communities = (0 until subs.length()).map { subs.getString(it) },
                    syncedAt = o.optLong("syncedAt"),
                    pinHash = o.optString("pinHash").ifBlank { null },
                    pinSalt = o.optString("pinSalt").ifBlank { null },
                    showNsfw = o.optBoolean("showNsfw"),
                    instance = o.optString("instance").ifBlank { null },
                    token = o.optString("token").ifBlank { null },
                    refreshToken = o.optString("refreshToken").ifBlank { null },
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
                    .put("knownAs", a.knownAs ?: "")
                    .put("subreddits", JSONArray(a.communities))
                    .put("syncedAt", a.syncedAt)
                    .put("pinHash", a.pinHash ?: "")
                    .put("pinSalt", a.pinSalt ?: "")
                    .put("showNsfw", a.showNsfw)
                    .put("kind", a.kind.name)
                    .put("instance", a.instance ?: "")
                    .put("token", a.token ?: "")
                    .put("refreshToken", a.refreshToken ?: ""),
            )
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).putString(KEY_ACTIVE, activeId).apply()
    }

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_ACTIVE = "active"
    }
}
