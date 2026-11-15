package app.lurk.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** How lively a joined community looks. */
enum class Health { Active, Quiet, Unknown }

/** The newest post seen from a community (Unix seconds; 0 for none), and whether its own feed was read. */
data class Activity(val newestPost: Long, val checkedAt: Long, val ownFeed: Boolean)

/**
 * When each community last had a post, as learned from the feeds the account reads: Home
 * shows which communities are posting, and a community's own feed shows when it last
 * did. Nothing is fetched just for this unless "Check" is pressed. Kept per account.
 */
class CommunityActivity(context: Context, accountId: String) {
    private val prefs = context.getSharedPreferences("community_activity", Context.MODE_PRIVATE)
    private val key = "activity:$accountId"
    private val known: MutableMap<String, Activity> = load()
    private var dirty = false
    private var lastSave = 0L

    var version by mutableIntStateOf(0)
        private set

    fun of(community: String): Activity? = known[community.lowercase()]

    /**
     * Notes a page of posts. [community] is the feed's own community, or null for feeds
     * that mix them (Home, All): those only show who's posting, never who isn't.
     */
    @Synchronized
    fun note(community: String?, posts: List<Post>, now: Long = System.currentTimeMillis()) {
        val newest = HashMap<String, Long>()
        posts.forEach { p ->
            val c = (p.community ?: return@forEach).lowercase()
            if (!p.stickied) newest[c] = maxOf(newest[c] ?: 0L, p.createdUtc)
        }
        if (community != null && !MergedCommunity.isKey(community)) {
            val c = community.lowercase()
            val n = newest[c] ?: posts.filterNot { it.stickied }.maxOfOrNull { it.createdUtc } ?: 0L
            known[c] = Activity(maxOf(n, known[c]?.newestPost ?: 0L), now, ownFeed = true)
            newest.remove(c)
        }
        newest.forEach { (c, n) ->
            val before = known[c]
            if (before == null || n > before.newestPost) known[c] = Activity(n, now, before?.ownFeed ?: false)
        }
        dirty = true
        version++
        if (now - lastSave > SAVE_EVERY_MS) save()
    }

    @Synchronized
    fun flush() {
        if (dirty) save()
    }

    private fun save() {
        val root = JSONObject()
        known.forEach { (c, a) -> root.put(c, JSONObject().put("n", a.newestPost).put("at", a.checkedAt).put("own", a.ownFeed)) }
        prefs.edit().putString(key, root.toString()).apply()
        dirty = false
        lastSave = System.currentTimeMillis()
    }

    private fun load(): MutableMap<String, Activity> {
        val root = runCatching { JSONObject(prefs.getString(key, null) ?: "{}") }.getOrNull() ?: JSONObject()
        val out = HashMap<String, Activity>()
        for (c in root.keys()) {
            val o = root.optJSONObject(c) ?: continue
            out[c] = Activity(o.optLong("n"), o.optLong("at"), o.optBoolean("own"))
        }
        return out
    }

    companion object {
        private const val SAVE_EVERY_MS = 10_000L
        /** Longer than this without a post is quiet. */
        const val QUIET_SECONDS = 30L * 24 * 60 * 60

        /** [a]'s health at [nowSeconds]: quiet only once its own feed has shown nothing recent. */
        fun health(a: Activity?, nowSeconds: Long = System.currentTimeMillis() / 1000): Health = when {
            a == null -> Health.Unknown
            a.newestPost > 0 && nowSeconds - a.newestPost <= QUIET_SECONDS -> Health.Active
            a.ownFeed -> Health.Quiet
            else -> Health.Unknown
        }
    }
}
