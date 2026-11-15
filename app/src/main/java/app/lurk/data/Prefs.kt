package app.lurk.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How a feed lays out its posts. */
enum class PostLayout(val label: String, val blurb: String) {
    Compact("Compact", "Titles first, small thumbnails"),
    Comfortable("Cards", "Rounded cards with media"),
    Large("Large media", "Edge-to-edge pictures, uncropped"),
    Gallery("Gallery", "A grid of pictures and videos");

    companion object {
        fun of(name: String?): PostLayout? = entries.firstOrNull { it.name == name }
    }
}

/** Choices for the feed budget, in posts. 0 is off. */
val FeedBudgetChoices = listOf(0, 20, 50, 100)

/**
 * Settings that belong to Lurk rather than to an account: how feeds look, how links open,
 * and how much to read in one go. Saved in SharedPreferences; reading them in Compose
 * recomposes on change.
 */
class LurkPrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("lurk_prefs", Context.MODE_PRIVATE)

    /** The layout for feeds that haven't been given one of their own. */
    var defaultLayout by mutableStateOf(PostLayout.of(prefs.getString(KEY_LAYOUT, null)) ?: PostLayout.Comfortable)
        private set

    /** Article links open in reader view rather than the browser. */
    var readerByDefault by mutableStateOf(prefs.getBoolean(KEY_READER, false))
        private set

    /** Tracking parameters come off links before they're opened or shared. */
    var cleanLinks by mutableStateOf(prefs.getBoolean(KEY_CLEAN, true))
        private set

    /** Posts to read before feeds pause; 0 is no limit. */
    var feedBudget by mutableIntStateOf(prefs.getInt(KEY_BUDGET, 0))
        private set

    fun changeDefaultLayout(layout: PostLayout) {
        defaultLayout = layout
        prefs.edit().putString(KEY_LAYOUT, layout.name).apply()
    }

    fun changeReaderByDefault(on: Boolean) {
        readerByDefault = on
        prefs.edit().putBoolean(KEY_READER, on).apply()
    }

    fun changeCleanLinks(on: Boolean) {
        cleanLinks = on
        prefs.edit().putBoolean(KEY_CLEAN, on).apply()
    }

    fun changeFeedBudget(posts: Int) {
        feedBudget = posts.coerceAtLeast(0)
        prefs.edit().putInt(KEY_BUDGET, feedBudget).apply()
        ReadingSession.restart()
    }

    /** [url] without tracking parameters, when that's switched on. */
    fun tidy(url: String): String = if (cleanLinks) LinkCleaner.clean(url) else url

    companion object {
        private const val KEY_LAYOUT = "layout"
        private const val KEY_READER = "readerByDefault"
        private const val KEY_CLEAN = "cleanLinks"
        private const val KEY_BUDGET = "feedBudget"

        @Volatile private var shared: LurkPrefs? = null

        fun get(context: Context): LurkPrefs = shared ?: synchronized(this) {
            shared ?: LurkPrefs(context.applicationContext).also { shared = it }
        }
    }
}

/**
 * The feed budget's count: posts read since the app was last away for a while. Feeds
 * pause once it reaches the budget, until "Keep going" grants another helping.
 */
object ReadingSession {
    /** Posts read this sitting. */
    var read by mutableIntStateOf(0)
        private set

    /** Helpings granted past the first, by "Keep going". */
    var extra by mutableIntStateOf(0)
        private set

    private var awaySince = 0L

    fun note(posts: Int) {
        read += posts
    }

    /** Whether a budget of [budget] posts is used up. */
    fun spent(budget: Int): Boolean = budget > 0 && read >= budget * (1 + extra)

    /** Another helping of the budget. */
    fun keepGoing() {
        extra++
    }

    fun restart() {
        read = 0
        extra = 0
    }

    fun onAway(now: Long = System.currentTimeMillis()) {
        awaySince = now
    }

    /** Back in the app: a long enough break starts a new sitting. */
    fun onBack(now: Long = System.currentTimeMillis()) {
        if (awaySince != 0L && now - awaySince >= BREAK_MS) restart()
        awaySince = 0L
    }

    /** How long away counts as a break. */
    const val BREAK_MS = 30 * 60 * 1000L
}
