package app.lurk.data

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One entry in the back stack. Each keeps its own loaded data and scroll position. */
sealed interface Screen

/** What a feed needs from its account beyond the site: what's been seen, what's muted, and where to report. */
class FeedHooks(
    /** Posts the account has had on screen; null keeps no record (previews, tests). */
    val seen: SeenPosts? = null,
    /** Whether to leave out posts already seen, read at each load. */
    val hideSeen: () -> Boolean = { false },
    /** The account's muted words, read at each load. */
    val mutes: () -> MuteFilter = { MuteFilter.None },
    /** Posts to read before the feed pauses; 0 for no limit. */
    val budget: () -> Int = { 0 },
    /** Reads a merged community's copies; null reads the feed's community from the site. */
    val merged: MergedFeed? = null,
    /** The layout to start with, and where a change of it is kept. */
    val layout: () -> PostLayout = { PostLayout.Comfortable },
    val saveLayout: (PostLayout) -> Unit = {},
    /** Each page as it arrives: [community] is the feed's, or null for mixed feeds. */
    val onPage: (community: String?, posts: List<Post>) -> Unit = { _, _ -> },
    /** Posts that stayed on screen a moment. */
    val onRead: (List<Post>) -> Unit = {},
    /** Posts left out of a page: seen before, or muted. */
    val onHidden: (seen: Int, muted: Int) -> Unit = { _, _ -> },
)

/** A list of posts: Home (community = null), a fixed feed like r/all, a community, or a merged one. */
class FeedScreen(
    val community: String?,
    sort: Sort?,
    private val service: Service,
    private val scope: CoroutineScope,
    private val hooks: FeedHooks = FeedHooks(),
) : Screen {
    var sort by mutableStateOf(sort)
        private set
    /** Everything loaded, muted posts included; [shown] is what's on screen. */
    val posts = mutableStateListOf<Post>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set
    /**
     * Replaced on refresh: a list keeps its place by post, so reusing it would jump to
     * wherever the post you were on landed in the new results instead of the top.
     */
    var listState by mutableStateOf(LazyListState())
        private set
    /** The same, for the gallery's grid. */
    var gridState by mutableStateOf(LazyStaggeredGridState())
        private set

    var layout by mutableStateOf(hooks.layout())
        private set

    /** When the posts shown were fetched (ms), and anything about that to show over them, for services that say (RSS). */
    var fetchedAt by mutableStateOf<Long?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /** Posts muted words caught, by name, with the rule each tripped. */
    val muted = mutableStateMapOf<String, String>()
    /** Shows the muted posts where they were, for a look. */
    var showMuted by mutableStateOf(false)

    /** Where the feed budget ran out: posts past this index wait for "Keep going". */
    var budgetCap by mutableStateOf<Int?>(if (ReadingSession.spent(hooks.budget())) 0 else null)
        private set

    private var after: String? = null
    private var job: Job? = null
    private var generation = 0
    /** The next first-page load is a pull to refresh. */
    private var force = false
    /** Whether loads since the last refresh left out posts already seen. */
    var skippedSeen by mutableStateOf(false)
        private set

    /** What's on screen: muted posts are left out unless asked for, and nothing past the budget. */
    val shown: List<Post>
        get() {
            val visible = if (showMuted || muted.isEmpty()) posts.toList() else posts.filter { it.name !in muted }
            return budgetCap?.let { visible.take(it) } ?: visible
        }

    val isMerged: Boolean get() = hooks.merged != null

    val label: String
        get() = community?.let { c ->
            service.fixedFeeds.firstOrNull { it.community.equals(c, ignoreCase = true) }?.label ?: service.naming.community(c)
        } ?: "Home"
    val webUrl: String get() = service.links.feed(community?.takeIf { !isMerged }, sort)

    /** Feeds that mix communities show each post's. */
    val showsCommunity: Boolean get() = isMerged || service.isMixed(community)

    /** What a slow first load is waiting on, e.g. Reddit's RSS rate limit. */
    val loadingNote: String? get() = service.loadingNote

    /** Whether the feed budget has paused this feed. */
    val paused: Boolean get() = budgetCap != null

    fun loadMore() {
        if (loading || endReached || paused) return
        val gen = generation
        loading = true
        error = null
        job = scope.launch {
            try {
                var pages = 0
                // A first page of only seen or muted posts would look empty: keep going a little.
                do {
                    val first = after == null
                    val refresh = force && first
                    val page = hooks.merged?.page(sort, after, refresh) ?: service.feed(community, sort, after, refresh)
                    if (gen != generation) return@launch
                    if (first) {
                        fetchedAt = page.fetchedAt
                        notice = page.notice
                    }
                    force = false
                    hooks.onPage(community?.takeIf { !service.isMixed(it) && !isMerged }, page.posts)
                    posts.addAll(fresh(page.posts))
                    after = page.after
                    endReached = page.after == null
                    pages++
                } while (shown.isEmpty() && !endReached && pages < 5)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: "Something went wrong"
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    /**
     * Adds [extra] posts just below the screen, spread out, so they turn up as you scroll on
     * and nothing you're looking at moves. Skips any already here.
     */
    fun mixIn(extra: List<Post>, spacing: Int) {
        if (loading || paused) return
        val add = fresh(extra)
        if (add.isEmpty()) return
        val index = posts.withIndex().associate { (i, p) -> p.name to i }
        val lastShown = listState.layoutInfo.visibleItemsInfo.mapNotNull { index[it.key as? String] }.maxOrNull() ?: -1
        // One post below the screen's last, so it isn't added in view.
        val merged = interleave(posts.toList(), add, lastShown + 2, spacing)
        // Insert one at a time: replacing the whole list would make the list lose its place.
        merged.forEachIndexed { i, p -> if (p.name !in index) posts.add(i, p) }
    }

    /**
     * Of [incoming], the posts not already here, less any already seen if the account hides
     * those. Muted ones are kept, marked in [muted], so they can be shown on request.
     */
    private fun fresh(incoming: List<Post>): List<Post> {
        val have = posts.mapTo(HashSet()) { it.name }
        val skip = hooks.seen?.takeIf { hooks.hideSeen() }
        val mutes = hooks.mutes()
        var seenCount = 0
        var mutedCount = 0
        val out = incoming.filter { post ->
            if (!have.add(post.name)) return@filter false
            if (skip?.wasSeen(post.name) == true) {
                skippedSeen = true
                seenCount++
                return@filter false
            }
            mutes.match(post)?.let { rule ->
                muted[post.name] = rule
                mutedCount++
            }
            true
        }
        hooks.onHidden(seenCount, mutedCount)
        return out
    }

    /** Notes posts that have been on screen a moment. */
    fun markSeen(names: Collection<String>) {
        hooks.seen?.mark(names)
        val newly = names.filter { counted.add(it) }
        if (newly.isEmpty()) return
        val byName = posts.associateBy { it.name }
        hooks.onRead(newly.mapNotNull { byName[it] })
        ReadingSession.note(newly.size)
        checkBudget()
    }

    /** Posts counted toward stats and the budget on this screen, so scrolling back doesn't count twice. */
    private val counted = HashSet<String>()

    /** Once the budget's spent, the feed stops a little below what's on screen. */
    private fun checkBudget() {
        if (budgetCap != null || !ReadingSession.spent(hooks.budget())) return
        val list = shown
        val index = list.withIndex().associate { (i, p) -> p.name to i }
        val lastOnScreen = if (layout == PostLayout.Gallery) {
            gridState.layoutInfo.visibleItemsInfo.mapNotNull { index[it.key as? String] }.maxOrNull()
        } else {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { index[it.key as? String] }.maxOrNull()
        } ?: (list.size - 1)
        budgetCap = (lastOnScreen + 1).coerceAtMost(list.size)
    }

    /** Another helping of the feed budget. */
    fun keepGoing() {
        ReadingSession.keepGoing()
        budgetCap = null
        if (shown.size >= posts.size - 2) loadMore()
    }

    fun saveSeen() {
        hooks.seen?.flush()
    }

    /** Stops a load in progress, once the screen is gone. */
    fun cancel() {
        generation++
        job?.cancel()
        loading = false
    }

    /** [pulled]: asked for by the user, so a service that keeps pages (RSS) fetches even a fairly new one. */
    fun refresh(pulled: Boolean = true) {
        force = pulled
        generation++
        job?.cancel()
        posts.clear()
        muted.clear()
        showMuted = false
        skippedSeen = false
        after = null
        endReached = false
        loading = false
        error = null
        listState = LazyListState()
        gridState = LazyStaggeredGridState()
        // A new budget only comes with a new sitting; until then a refreshed feed stays paused.
        budgetCap = if (ReadingSession.spent(hooks.budget())) 0 else null
        loadMore()
    }

    fun changeSort(newSort: Sort) {
        if (newSort == sort) return
        sort = newSort
        refresh()
    }

    /** Shows [newLayout] without keeping it for this feed: the default changed. */
    fun useLayout(newLayout: PostLayout) {
        layout = newLayout
    }

    fun changeLayout(newLayout: PostLayout) {
        if (newLayout == layout) return
        layout = newLayout
        hooks.saveLayout(newLayout)
    }
}

/**
 * [base] with [extra] slotted in: the first at [start], then one every [every] posts of
 * [base]. Whatever doesn't fit goes on the end.
 */
fun interleave(base: List<Post>, extra: List<Post>, start: Int, every: Int): List<Post> {
    val inBase = base.mapTo(HashSet()) { it.name }
    val queue = ArrayDeque(extra.filter { it.name !in inBase })
    if (queue.isEmpty()) return base
    val out = ArrayList<Post>(base.size + queue.size)
    var next = start.coerceAtLeast(0)
    var taken = 0
    for (p in base) {
        while (taken == next && queue.isNotEmpty()) {
            out += queue.removeFirst()
            next += every.coerceAtLeast(1)
        }
        out += p
        taken++
    }
    out.addAll(queue)
    return out
}

/** A post and its comments. */
class ThreadScreen(
    /** What the service opens: usually the post's [Post.threadRef]. */
    val ref: String,
    preview: Post?,
    private val service: Service,
    private val scope: CoroutineScope,
) : Screen {
    var post by mutableStateOf(preview)
        private set
    val items = mutableStateListOf<ThreadItem>()
    var sort by mutableStateOf(service.commentSorts.firstOrNull())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var isSingleThread by mutableStateOf(false)
        private set
    /** Comments the user collapsed, by name. */
    val collapsed = mutableStateMapOf<String, Boolean>()
    /** "Load more" rows currently loading, by key. */
    val loadingMore = mutableStateMapOf<String, Boolean>()
    val listState = LazyListState()

    private var job: Job? = null
    private var generation = 0

    val webUrl: String get() = service.links.thread(ref)

    fun load() {
        generation++
        val gen = generation
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                val data = service.thread(ref, sort)
                if (gen != generation) return@launch
                post = data.post
                isSingleThread = data.isSingleThread
                items.clear()
                items.addAll(data.items)
                collapsed.clear()
                loadingMore.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: "Something went wrong"
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun changeSort(newSort: Sort) {
        if (newSort == sort) return
        sort = newSort
        load()
    }

    fun toggle(comment: Comment) {
        if (collapsed.remove(comment.name) == null) collapsed[comment.name] = true
    }

    /** Loads a "load more" row in place. Returns false for rows that need their own page. */
    fun expand(more: More, onError: (String) -> Unit): Boolean {
        val post = post ?: return true
        if (more.children.isEmpty()) return false
        if (loadingMore.containsKey(more.key)) return true
        loadingMore[more.key] = true
        val gen = generation
        scope.launch {
            try {
                val loaded = service.moreChildren(post, more, sort)
                if (gen != generation) return@launch
                val index = items.indexOfFirst { it.key == more.key }
                if (index >= 0) {
                    items.removeAt(index)
                    // Rows must have unique keys; skip anything sent twice.
                    val present = items.mapTo(HashSet()) { it.key }
                    items.addAll(index, loaded.filter { present.add(it.key) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Couldn't load more comments")
            } finally {
                loadingMore.remove(more.key)
            }
        }
        return true
    }

    /** The thread to open for a "continue this thread" row. */
    fun continueRef(more: More): String = service.continueRef(post, ref, more)

    /** Visible rows, skipping the replies under collapsed comments. */
    fun visibleItems(): List<ThreadItem> {
        val out = ArrayList<ThreadItem>(items.size)
        var hideBelow = Int.MAX_VALUE
        for (item in items) {
            if (item.depth > hideBelow) continue
            hideBelow = Int.MAX_VALUE
            out += item
            if (item is Comment && collapsed.containsKey(item.name)) hideBelow = item.depth
        }
        return out
    }
}

/**
 * A community's game threads as a scoreboard: each game with its result and links to its
 * game and post-game threads, a day at a time.
 */
class GamesScreen(
    val community: String,
    private val service: Service,
    private val scope: CoroutineScope,
) : Screen {
    var days by mutableStateOf<List<GameDay>>(emptyList())
        private set
    /** The day shown; null shows the newest. */
    var selected by mutableStateOf<java.time.LocalDate?>(null)
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Whether a load has finished, so "no games" isn't shown before the first one. */
    var loaded by mutableStateOf(false)
        private set
    var fetchedAt by mutableStateOf<Long?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    val listState = LazyListState()

    private var job: Job? = null
    private var generation = 0

    val webUrl: String get() = service.links.feed(community, null)

    /** What a slow load is waiting on, e.g. Reddit's RSS rate limit. */
    val loadingNote: String? get() = service.loadingNote

    val day: GameDay? get() = days.firstOrNull { it.date == selected } ?: days.firstOrNull()

    /** [refresh]: asked for by the user, so a service that keeps pages (RSS) fetches again. */
    fun load(refresh: Boolean = false) {
        generation++
        val gen = generation
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                val page = service.gameThreads(community, refresh)
                if (gen != generation) return@launch
                days = GameThreads.board(page.posts)
                fetchedAt = page.fetchedAt
                notice = page.notice
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: "Something went wrong"
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        loading = false
    }
}

/** A page shown in the visible WebView: sign-in, inbox, user pages, anything without a native screen. */
class WebScreen(var url: String) : Screen

enum class Vote { Up, None, Down }

/**
 * Votes and saves, applied to the UI at once and sent to the service. Overrides
 * are kept by name so every screen showing the same post or comment agrees.
 */
class Actions(
    private val service: Service,
    private val scope: CoroutineScope,
    private val signedIn: () -> Boolean,
    private val onMessage: (String) -> Unit,
) {
    private val votes = mutableStateMapOf<String, Vote>()
    private val saves = mutableStateMapOf<String, Boolean>()

    /** Whether to offer a downvote button. */
    val downvotes: Boolean get() = service.downvotes

    fun vote(name: String, base: Boolean?): Vote = votes[name] ?: base.toVote()

    fun saved(name: String, base: Boolean): Boolean = saves[name] ?: base

    /** The score adjusted for a vote the user changed since the data loaded. */
    fun score(name: String, baseScore: Int, baseLikes: Boolean?): Int =
        baseScore - baseLikes.toVote().weight + vote(name, baseLikes).weight

    fun toggleVote(name: String, base: Boolean?, up: Boolean) {
        if (!signedIn()) {
            onMessage("Sign in to vote")
            return
        }
        val before = vote(name, base)
        val target = if (up) Vote.Up else Vote.Down
        val next = if (before == target) Vote.None else target
        votes[name] = next
        scope.launch {
            try {
                service.vote(name, next.weight)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                votes[name] = before
                onMessage("Vote failed: ${e.message}")
            }
        }
    }

    fun toggleSave(name: String, base: Boolean) {
        if (!signedIn()) {
            onMessage("Sign in to save")
            return
        }
        val before = saved(name, base)
        saves[name] = !before
        scope.launch {
            try {
                service.setSaved(name, !before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saves[name] = before
                onMessage("Save failed: ${e.message}")
            }
        }
    }

    private val Vote.weight: Int get() = when (this) { Vote.Up -> 1; Vote.None -> 0; Vote.Down -> -1 }
    private fun Boolean?.toVote(): Vote = when (this) { true -> Vote.Up; false -> Vote.Down; null -> Vote.None }
}
