package app.lurk.data

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The pages of Settings. */
enum class SettingsPage(val title: String) {
    Main("Settings"),
    Mutes("Muted words"),
    Stats("Your reading"),
    Health("Community health"),
    Merged("Merged communities"),
}

/** A page of Settings. */
class SettingsScreen(val page: SettingsPage) : Screen {
    val listState = LazyListState()
}

/** The account's Read later shelf. */
class ReadLaterScreen : Screen {
    val listState = LazyListState()
}

/**
 * Community health: each joined community, and whether it's still posting. What feeds have
 * shown is used first; "Check" reads each unknown one's own feed, gently, one at a time.
 */
class HealthCheck(
    private val service: Service,
    private val activity: CommunityActivity,
    private val scope: CoroutineScope,
) {
    /** What the Lemmy community index says, by community (Lemmy only). */
    val index = mutableStateMapOf<String, IndexHealth>()
    var checking by mutableStateOf(false)
        private set
    /** How far a check has got, as done / total. */
    var progress by mutableStateOf(0 to 0)
        private set

    fun loadIndex(communities: List<String>) {
        val lemmy = service as? LemmyService ?: return
        scope.launch {
            try {
                index.putAll(lemmy.indexHealth(communities))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No index at hand: health falls back to what feeds have shown.
            }
        }
    }

    /** Reads the own feed of each of [communities] not known to be active, a few seconds apart. */
    fun check(communities: List<String>) {
        if (checking) return
        val todo = communities.filter { CommunityActivity.health(activity.of(it)) != Health.Active }
        if (todo.isEmpty()) return
        checking = true
        progress = 0 to todo.size
        scope.launch {
            try {
                todo.forEachIndexed { i, c ->
                    try {
                        val newest = service.feedSorts.firstOrNull { it.key.equals("new", ignoreCase = true) }
                        activity.note(c, service.feed(c, newest, null).posts)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Unreachable right now; it stays unknown.
                    }
                    progress = (i + 1) to todo.size
                    if (i < todo.lastIndex) delay(if (service.kind == ServiceKind.RedditRss) RSS_GAP_MS else CHECK_GAP_MS)
                }
            } finally {
                activity.flush()
                checking = false
            }
        }
    }

    private companion object {
        /** Between feeds, to go easy on the site. */
        const val CHECK_GAP_MS = 1_500L
        /** Reddit's RSS allows few requests a minute. */
        const val RSS_GAP_MS = 8_000L
    }
}
