package app.lurk.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** A post put aside to read later, and when. */
data class LaterPost(val post: Post, val addedAt: Long)

/**
 * An account's Read later shelf: posts kept on the phone, newest first, until they're
 * read or taken off. Unlike saving, the site never hears about it.
 */
class ReadLater(context: Context, accountId: String) {
    private val prefs = context.getSharedPreferences("read_later", Context.MODE_PRIVATE)
    private val key = "later:$accountId"

    /** Newest first. */
    val items = mutableStateListOf<LaterPost>().apply { addAll(load()) }

    operator fun contains(name: String): Boolean = items.any { it.post.name == name }

    fun add(post: Post) {
        if (post.name in this) return
        items.add(0, LaterPost(post, System.currentTimeMillis()))
        while (items.size > LIMIT) items.removeAt(items.lastIndex)
        save()
    }

    /** Takes [name] off the shelf; returns what it was, for Undo. */
    fun remove(name: String): LaterPost? {
        val i = items.indexOfFirst { it.post.name == name }
        if (i < 0) return null
        return items.removeAt(i).also { save() }
    }

    /** Puts back what [remove] took off, where it was by date. */
    fun restore(item: LaterPost) {
        if (item.post.name in this) return
        val at = items.indexOfFirst { it.addedAt < item.addedAt }.let { if (it < 0) items.size else it }
        items.add(at, item)
        save()
    }

    fun toggle(post: Post): Boolean {
        val adding = post.name !in this
        if (adding) add(post) else remove(post.name)
        return adding
    }

    fun clear() {
        items.clear()
        save()
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("at", it.addedAt).put("post", PostCodec.encode(it.post))) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun load(): List<LaterPost> {
        val arr = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching { LaterPost(PostCodec.decode(o.getJSONObject("post")), o.optLong("at")) }.getOrNull()
        }
    }

    private companion object {
        const val LIMIT = 500
    }
}
