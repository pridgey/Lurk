package app.lurk.data

import android.content.Context
import org.json.JSONObject

/**
 * Posts an account has had on screen in its feeds, so feeds can leave them out when the
 * account hides seen posts. Kept on the device, so it works the same on every site.
 */
class SeenPosts(context: Context, accountId: String) {
    private val prefs = context.getSharedPreferences("seen_posts", Context.MODE_PRIVATE)
    private val key = "seen:$accountId"
    /** Post name to when it was first seen (ms), oldest first. */
    private val seen = load()
    private var dirty = false
    private var lastSave = 0L

    fun wasSeen(name: String): Boolean = name in seen

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

    /** Writes anything noted since the last save. */
    fun flush() {
        if (dirty) save()
    }

    private fun save() {
        val extra = seen.size - LIMIT
        if (extra > 0) {
            val oldest = seen.keys.iterator()
            repeat(extra) { oldest.next(); oldest.remove() }
        }
        val json = JSONObject().apply { seen.forEach { (name, at) -> put(name, at) } }
        prefs.edit().putString(key, json.toString()).apply()
        dirty = false
        lastSave = System.currentTimeMillis()
    }

    private fun load(): LinkedHashMap<String, Long> {
        val cutoff = System.currentTimeMillis() - DAYS * 24L * 60 * 60 * 1000
        val saved = runCatching { JSONObject(prefs.getString(key, null) ?: "{}") }.getOrNull() ?: JSONObject()
        val entries = ArrayList<Pair<String, Long>>()
        for (name in saved.keys()) {
            val at = saved.optLong(name)
            if (at >= cutoff) entries += name to at
        }
        entries.sortBy { it.second }
        return entries.toMap(LinkedHashMap())
    }

    private companion object {
        const val LIMIT = 5_000
        const val DAYS = 14
        const val SAVE_EVERY_MS = 5_000L
    }
}
