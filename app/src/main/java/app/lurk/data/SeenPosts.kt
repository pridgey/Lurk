package app.lurk.data

import android.content.Context
import org.json.JSONObject

/**
 * Posts an account has had on screen in its feeds, and the ones it hid. A post seen on an
 * earlier load offers a hide button; hidden posts stay out of feeds. Kept on the device,
 * so it works the same on every site.
 */
class SeenPosts(context: Context, accountId: String) {
    private val prefs = context.getSharedPreferences("seen_posts", Context.MODE_PRIVATE)
    private val seenKey = "seen:$accountId"
    private val hiddenKey = "hidden:$accountId"
    /** Post name to when it was first seen or hidden (ms), oldest first. */
    private val seen = load(seenKey, SEEN_DAYS)
    private val hidden = load(hiddenKey, HIDDEN_DAYS)
    private var dirty = false
    private var lastSave = 0L

    fun wasSeen(name: String): Boolean = name in seen

    fun isHidden(name: String): Boolean = name in hidden

    /** Notes posts that were on screen. Saves every few seconds rather than on every call. */
    fun mark(names: Collection<String>) {
        val now = System.currentTimeMillis()
        for (name in names) {
            if (name !in seen) {
                seen[name] = now
                dirty = true
            }
        }
        if (dirty && now - lastSave > SAVE_EVERY_MS) save()
    }

    fun hide(name: String) {
        hidden.remove(name)
        hidden[name] = System.currentTimeMillis()
        save()
    }

    fun unhide(name: String) {
        if (hidden.remove(name) != null) save()
    }

    /** Writes anything noted since the last save. */
    fun flush() {
        if (dirty) save()
    }

    private fun save() {
        trim(seen, SEEN_LIMIT)
        trim(hidden, HIDDEN_LIMIT)
        prefs.edit()
            .putString(seenKey, encode(seen))
            .putString(hiddenKey, encode(hidden))
            .apply()
        dirty = false
        lastSave = System.currentTimeMillis()
    }

    private fun load(key: String, days: Int): LinkedHashMap<String, Long> {
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        val saved = runCatching { JSONObject(prefs.getString(key, null) ?: "{}") }.getOrNull() ?: JSONObject()
        val entries = ArrayList<Pair<String, Long>>()
        for (name in saved.keys()) {
            val at = saved.optLong(name)
            if (at >= cutoff) entries += name to at
        }
        entries.sortBy { it.second }
        return entries.toMap(LinkedHashMap())
    }

    private fun trim(map: LinkedHashMap<String, Long>, limit: Int) {
        val extra = map.size - limit
        if (extra <= 0) return
        val oldest = map.keys.iterator()
        repeat(extra) { oldest.next(); oldest.remove() }
    }

    private fun encode(map: Map<String, Long>): String =
        JSONObject().apply { map.forEach { (name, at) -> put(name, at) } }.toString()

    private companion object {
        const val SEEN_LIMIT = 4_000
        const val HIDDEN_LIMIT = 2_000
        const val SEEN_DAYS = 14
        const val HIDDEN_DAYS = 30
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val SAVE_EVERY_MS = 5_000L
    }
}
