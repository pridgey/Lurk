package app.lurk.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import org.json.JSONObject

/** One day of reading. */
data class DayStats(
    val date: LocalDate,
    /** Posts that stayed on screen a moment. */
    val read: Int = 0,
    /** Posts left out because they'd been seen before. */
    val hiddenSeen: Int = 0,
    /** Posts left out by muted words. */
    val hiddenMuted: Int = 0,
    /** Threads opened. */
    val threads: Int = 0,
    /** Time with Lurk open, in ms. */
    val activeMs: Long = 0,
    /** Posts read by where they're from ("r/pics", "c/linux@lemmy.ml"). */
    val communities: Map<String, Int> = emptyMap(),
)

/**
 * What you've read, day by day, for the Stats page. Kept on the phone only, across all
 * accounts, for [KEEP_DAYS] days. Writes are batched so scrolling stays smooth.
 */
class ReadingStats private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("reading_stats", Context.MODE_PRIVATE)
    private val days: MutableMap<LocalDate, DayStats> = load()
    private var dirty = false
    private var lastSave = 0L

    /** Bumped on every change, so screens reading the stats recompose. */
    var version by mutableIntStateOf(0)
        private set

    @Synchronized
    fun recordRead(communities: List<String>, today: LocalDate = LocalDate.now()) {
        if (communities.isEmpty()) return
        change(today) { d ->
            val counts = d.communities.toMutableMap()
            communities.forEach { c -> counts[c] = (counts[c] ?: 0) + 1 }
            d.copy(read = d.read + communities.size, communities = counts)
        }
    }

    @Synchronized
    fun recordHidden(seen: Int, muted: Int, today: LocalDate = LocalDate.now()) {
        if (seen == 0 && muted == 0) return
        change(today) { it.copy(hiddenSeen = it.hiddenSeen + seen, hiddenMuted = it.hiddenMuted + muted) }
    }

    @Synchronized
    fun recordThread(today: LocalDate = LocalDate.now()) = change(today) { it.copy(threads = it.threads + 1) }

    @Synchronized
    fun recordTime(ms: Long, today: LocalDate = LocalDate.now()) {
        if (ms <= 0) return
        // A day can't hold more than a day; guards against clock jumps.
        change(today) { it.copy(activeMs = (it.activeMs + ms).coerceAtMost(DAY_MS)) }
    }

    /** The last [count] days, oldest first, with empty days filled in. */
    @Synchronized
    fun lastDays(count: Int, today: LocalDate = LocalDate.now()): List<DayStats> =
        (count - 1 downTo 0).map { back -> today.minusDays(back.toLong()).let { days[it] ?: DayStats(it) } }

    /** Where most reading happened over [stretch], busiest first. */
    fun topCommunities(stretch: List<DayStats>, limit: Int = 8): List<Pair<String, Int>> {
        val totals = HashMap<String, Int>()
        stretch.forEach { d -> d.communities.forEach { (c, n) -> totals[c] = (totals[c] ?: 0) + n } }
        return totals.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    @Synchronized
    fun clear() {
        days.clear()
        prefs.edit().clear().apply()
        version++
    }

    /** Writes anything not yet saved. */
    @Synchronized
    fun flush() {
        if (dirty) save()
    }

    private fun change(day: LocalDate, update: (DayStats) -> DayStats) {
        days[day] = update(days[day] ?: DayStats(day))
        dirty = true
        version++
        val now = System.currentTimeMillis()
        if (now - lastSave > SAVE_EVERY_MS) save()
    }

    private fun save() {
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS)
        days.keys.removeAll { it.isBefore(cutoff) }
        val root = JSONObject()
        days.values.forEach { d ->
            root.put(
                d.date.toString(),
                JSONObject()
                    .put("r", d.read).put("s", d.hiddenSeen).put("m", d.hiddenMuted)
                    .put("t", d.threads).put("a", d.activeMs)
                    .put("c", JSONObject(d.communities as Map<*, *>)),
            )
        }
        prefs.edit().putString(KEY, root.toString()).apply()
        dirty = false
        lastSave = System.currentTimeMillis()
    }

    private fun load(): MutableMap<LocalDate, DayStats> {
        val root = runCatching { JSONObject(prefs.getString(KEY, null) ?: "{}") }.getOrNull() ?: JSONObject()
        val out = HashMap<LocalDate, DayStats>()
        for (key in root.keys()) {
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
            val o = root.optJSONObject(key) ?: continue
            val c = o.optJSONObject("c")
            out[date] = DayStats(
                date = date,
                read = o.optInt("r"), hiddenSeen = o.optInt("s"), hiddenMuted = o.optInt("m"),
                threads = o.optInt("t"), activeMs = o.optLong("a"),
                communities = c?.keys()?.asSequence()?.associateWith { c.optInt(it) }.orEmpty(),
            )
        }
        return out
    }

    companion object {
        private const val KEY = "days"
        const val KEEP_DAYS = 120L
        private const val SAVE_EVERY_MS = 10_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        /** A rough guess at the time one hidden post would have taken to scroll past and read. */
        const val SECONDS_PER_HIDDEN_POST = 6

        @Volatile private var shared: ReadingStats? = null

        fun get(context: Context): ReadingStats = shared ?: synchronized(this) {
            shared ?: ReadingStats(context.applicationContext).also { shared = it }
        }
    }
}
