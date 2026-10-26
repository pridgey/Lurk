package app.lurk.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether Reddit opens in Chrome instead of Lurk's own screens. Reddit blocks Lurk's
 * built-in browser ("You've been blocked by network security") while letting Chrome
 * through, and an app can't read what a Chrome tab shows. So once the block is seen,
 * Reddit accounts become a launcher for Chrome tabs and stop making requests that
 * would only be refused. Remembered across launches; "Try again" turns it off.
 */
object RedditAccess {

    private var prefs: android.content.SharedPreferences? = null

    var inChrome by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("reddit", Context.MODE_PRIVATE).also {
            inChrome = it.getBoolean(KEY, false)
        }
    }

    /** Reddit sent its block page. */
    fun blocked() = set(true)

    /** Check whether Lurk's own browser gets through again. */
    fun retry() = set(false)

    private fun set(on: Boolean) {
        if (inChrome == on) return
        inChrome = on
        prefs?.edit()?.putBoolean(KEY, on)?.apply()
    }

    private const val KEY = "inChrome"

    /** Where each Reddit page opens in Chrome. */
    object Urls {
        private const val ORIGIN = "https://www.reddit.com"
        const val HOME = "$ORIGIN/"
        const val LOGIN = "$ORIGIN/login/"
        const val COMMUNITIES = "$ORIGIN/best/communities/1/"
        fun community(name: String) = "$ORIGIN/r/$name/"
    }
}
