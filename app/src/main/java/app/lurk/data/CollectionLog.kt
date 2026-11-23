package app.lurk.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One thing background collection did. [count] is how many times in a row it happened; [at] is the latest. */
data class CollectionLogEntry(val at: Long, val message: String, val count: Int = 1)

/** What background collection has been doing, kept on the phone across restarts. Oldest first. */
object CollectionLog {

    const val MAX_ENTRIES = 200

    private fun prefs(context: Context) = context.getSharedPreferences("collected_log", Context.MODE_PRIVATE)

    @Synchronized
    fun add(context: Context, message: String, at: Long = System.currentTimeMillis()) {
        write(context, append(read(context), message, at))
    }

    @Synchronized
    fun read(context: Context): List<CollectionLogEntry> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CollectionLogEntry(o.getLong("at"), o.getString("message"), o.optInt("count", 1))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** [entries] plus [message]. A repeat of the last line counts up, so idle wake-ups don't push fetches out. */
    fun append(entries: List<CollectionLogEntry>, message: String, at: Long): List<CollectionLogEntry> {
        val last = entries.lastOrNull()
        val next = if (last != null && last.message == message) {
            entries.dropLast(1) + last.copy(at = at, count = last.count + 1)
        } else {
            entries + CollectionLogEntry(at, message)
        }
        return next.takeLast(MAX_ENTRIES)
    }

    fun line(e: CollectionLogEntry): String =
        SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date(e.at)) + "  " + e.message +
            if (e.count > 1) " (×${e.count})" else ""

    private fun write(context: Context, entries: List<CollectionLogEntry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("at", it.at).put("message", it.message).put("count", it.count)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private const val KEY = "entries"
}
