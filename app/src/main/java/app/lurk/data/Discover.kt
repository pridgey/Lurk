package app.lurk.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

/** Communities an account has swiped past in Discover, so the deck doesn't repeat itself. Newest last. */
class SeenCommunities(context: Context, accountId: String) {
    private val prefs = context.getSharedPreferences("discover", Context.MODE_PRIVATE)
    private val key = "seen:$accountId"
    private val names = LinkedHashSet<String>().apply {
        val saved = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrNull() ?: JSONArray()
        for (i in 0 until saved.length()) add(saved.optString(i))
    }

    operator fun contains(name: String) = name.lowercase() in names

    fun add(name: String) {
        val n = name.lowercase()
        names.remove(n)
        names.add(n)
        while (names.size > LIMIT) names.remove(names.first())
        save()
    }

    fun remove(name: String) {
        if (names.remove(name.lowercase())) save()
    }

    fun clear() {
        names.clear()
        save()
    }

    private fun save() {
        prefs.edit().putString(key, JSONArray(names.toList()).toString()).apply()
    }

    private companion object {
        const val LIMIT = 2_000
    }
}

/** A few of a community's best recent posts, shown on its Discover card. */
sealed interface Peek {
    data object Loading : Peek
    data class Loaded(val posts: List<Post>) : Peek
    data object Failed : Peek
}

/**
 * Discover: a shuffled deck of communities to swipe through. Right joins, left skips;
 * either way the community goes on the account's seen list and won't come back
 * (until "Start over"). The deck tops itself up from the site's busiest communities.
 * Typing in its search box swaps the deck for a list of communities on that topic.
 */
class DiscoverScreen(
    private val service: Service,
    private val scope: CoroutineScope,
    private val seen: SeenCommunities,
    /** Whether the account follows a community, by name. */
    val isSubscribed: (String) -> Boolean,
    /** Whether the account has opted into NSFW communities. */
    private val showNsfw: () -> Boolean,
    private val subscribe: (name: String, subscribed: Boolean) -> Unit,
) : Screen {
    /** The deck, top card first. */
    val deck = mutableStateListOf<CommunityInfo>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** No more pages to draw from. */
    var exhausted by mutableStateOf(false)
        private set
    var joined by mutableIntStateOf(0)
        private set
    var skipped by mutableIntStateOf(0)
        private set
    /** The last swipe, which Undo takes back. */
    var last by mutableStateOf<Swipe?>(null)
        private set
    val peeks = mutableStateMapOf<String, Peek>()
    var sort by mutableStateOf(service.discoverSorts.firstOrNull())
        private set

    /** Topics the deck can narrow to; empty where the site has none. */
    val topics: List<Topic> = service.discoverTopics
    /** The deck's topic; null deals from everything. */
    var topic by mutableStateOf<Topic?>(null)
        private set

    class Swipe(val community: CommunityInfo, val joined: Boolean)

    val webUrl: String get() = service.links.communities

    /** The heading over a card's posts: "Top this week". */
    val peekLabel: String get() = service.peekLabel

    /** Whether the first deal has begun. */
    var started by mutableStateOf(false)
        private set

    private var after: String? = null
    private var generation = 0
    private var job: Job? = null
    /** Lowercase names already dealt this time round, so a page that repeats one doesn't deal it twice. */
    private val dealt = HashSet<String>()

    fun start() {
        if (started) return
        started = true
        topUp()
    }

    /** Swipes the top card away: right joins, left skips. */
    fun swipe(join: Boolean) {
        val top = deck.firstOrNull() ?: return
        deck.removeAt(0)
        seen.add(top.name)
        if (join) {
            joined++
            subscribe(top.name, true)
        } else {
            skipped++
        }
        last = Swipe(top, join)
        topUp()
        peekAhead()
    }

    /** Puts the last swiped card back on top, leaving the community again if it was joined. */
    fun undo() {
        val swipe = last ?: return
        last = null
        deck.add(0, swipe.community)
        seen.remove(swipe.community.name)
        if (swipe.joined) {
            joined--
            subscribe(swipe.community.name, false)
        } else {
            skipped--
        }
    }

    /** Reads on for more after a stretch where everything was seen or followed already. */
    fun dealMore() = topUp()

    fun changeSort(newSort: Sort) {
        if (newSort == sort) return
        sort = newSort
        refresh()
    }

    /** Deals from [newTopic] alone, or from everything again when it's the one already picked. */
    fun changeTopic(newTopic: Topic?) {
        topic = if (newTopic == topic) null else newTopic
        refresh()
    }

    /** Deals a fresh deck, still leaving out what's been seen. */
    fun refresh() {
        generation++
        job?.cancel()
        deck.clear()
        dealt.clear()
        peeks.clear()
        after = null
        exhausted = false
        loading = false
        error = null
        last = null
        started = true
        topUp()
    }

    /** Forgets everything seen and deals again from the top. */
    fun startOver() {
        seen.clear()
        joined = 0
        skipped = 0
        refresh()
    }

    private fun topUp() {
        if (loading || exhausted || deck.size >= LOW_WATER) return
        val gen = generation
        loading = true
        error = null
        job = scope.launch {
            try {
                // A page can be all seen or followed already; read on a few pages before giving up for now.
                var pages = 0
                while (deck.size < LOW_WATER && (pages < MAX_PAGES_PER_TOP_UP || (deck.isEmpty() && pages < MAX_PAGES_WHEN_EMPTY))) {
                    val page = service.discover(sort, after, topic)
                    if (gen != generation) return@launch
                    after = page.after
                    pages++
                    val fresh = page.communities.filter {
                        dealt.add(it.name.lowercase()) && !it.subscribed && it.name !in seen &&
                            !isSubscribed(it.name) && (it.subscribers ?: MIN_MEMBERS) >= MIN_MEMBERS && it.posts != 0 && allowed(it)
                    }
                    deck.addAll(fresh.shuffled())
                    if (page.after == null) {
                        exhausted = true
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: "Couldn't load communities"
            } finally {
                if (gen == generation) loading = false
            }
            if (gen == generation) peekAhead()
        }
    }

    private fun allowed(c: CommunityInfo) = !c.over18 || showNsfw()

    // --- Search ---

    /** What's in the search box. Blank shows the deck. */
    var query by mutableStateOf("")
        private set
    /** The query [results] are for, once its first page is in. */
    var searched by mutableStateOf<String?>(null)
        private set
    val results = mutableStateListOf<CommunityInfo>()
    var searching by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    /** No more results for this query. */
    var searchEnded by mutableStateOf(false)
        private set
    private var searchAfter: String? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0

    val isSearching: Boolean get() = query.isNotBlank()

    /** Updates the search box; searches once typing pauses. */
    fun type(text: String) {
        query = text
        startSearch(debounce = true)
    }

    /** Searches now, as when the keyboard's Search is pressed. */
    fun submitSearch() {
        if (query.trim() != searched || searchError != null) startSearch(debounce = false)
    }

    fun clearSearch() {
        query = ""
        startSearch(debounce = false)
    }

    /** Joins (or leaves) a community from the results. */
    fun toggleJoin(community: CommunityInfo) {
        subscribe(community.name, !isSubscribed(community.name))
    }

    /** The next page of results, as the list nears its end. */
    fun searchMore() {
        if (searching || searchEnded || searchError != null || searched == null) return
        fetchResults(searched!!, searchGeneration, delayMs = 0)
    }

    /** After an error: the whole search again, or just the page that failed. */
    fun retrySearch() {
        if (results.isEmpty()) {
            startSearch(debounce = false)
        } else {
            searchError = null
            searchMore()
        }
    }

    private fun startSearch(debounce: Boolean) {
        val q = query.trim()
        searchGeneration++
        searchJob?.cancel()
        searching = false
        searchError = null
        if (q.length < MIN_QUERY) {
            searched = null
            results.clear()
            searchEnded = false
            return
        }
        searchAfter = null
        searchEnded = false
        // Keep the old results up while the new ones load, so the list doesn't flash empty.
        fetchResults(q, searchGeneration, delayMs = if (debounce) TYPING_PAUSE_MS else 0, fresh = true)
    }

    private fun fetchResults(q: String, gen: Int, delayMs: Long, fresh: Boolean = false) {
        searching = true
        searchJob = scope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                val page = service.searchCommunities(q, if (fresh) null else searchAfter)
                if (gen != searchGeneration) return@launch
                if (fresh) results.clear()
                val have = results.mapTo(HashSet()) { it.name.lowercase() }
                results.addAll(page.communities.filter { have.add(it.name.lowercase()) && allowed(it) })
                searched = q
                searchAfter = page.after
                searchEnded = page.after == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == searchGeneration) {
                    if (fresh) results.clear()
                    searched = q
                    searchError = e.message ?: "Couldn't search"
                }
            } finally {
                if (gen == searchGeneration) searching = false
            }
        }
    }

    /** Loads the top posts for the cards about to be seen. */
    private fun peekAhead() {
        val sort = service.peekSort
        deck.take(PEEK_AHEAD).forEach { community ->
            if (peeks.containsKey(community.name)) return@forEach
            peeks[community.name] = Peek.Loading
            scope.launch {
                peeks[community.name] = try {
                    val posts = service.feed(community.name, sort, null).posts
                    Peek.Loaded(posts.filterNot { it.stickied }.take(PEEK_POSTS))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Peek.Failed
                }
            }
        }
    }

    private companion object {
        /** Fetch more when the deck gets this low. */
        const val LOW_WATER = 6
        const val MAX_PAGES_PER_TOP_UP = 3
        /** With nothing left to show, read further before asking. */
        const val MAX_PAGES_WHEN_EMPTY = 8
        /** Smaller than this isn't much of a community to suggest yet. */
        const val MIN_MEMBERS = 10
        const val PEEK_AHEAD = 3
        const val PEEK_POSTS = 3
        /** Shorter than this matches nearly everything. */
        const val MIN_QUERY = 2
        const val TYPING_PAUSE_MS = 400L
    }
}
