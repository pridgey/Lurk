package app.lurk.data

import androidx.compose.foundation.lazy.LazyListState
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

/** A list of posts: the front page (subreddit = null) or a subreddit. */
class FeedScreen(
    val subreddit: String?,
    sort: FeedSort,
    private val api: RedditApi,
    private val scope: CoroutineScope,
) : Screen {
    var sort by mutableStateOf(sort)
        private set
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

    private var after: String? = null
    private var job: Job? = null
    private var generation = 0

    val label: String get() = subreddit?.let { "r/$it" } ?: "Home"
    val webUrl: String get() = api.feedUrl(subreddit, sort)

    /** Front page, r/all, r/popular and multireddits mix subreddits, so posts show theirs. */
    val showsSubreddit: Boolean
        get() = subreddit == null || subreddit.lowercase() in setOf("all", "popular") || '+' in subreddit

    fun loadMore() {
        if (loading || endReached) return
        val gen = generation
        loading = true
        error = null
        job = scope.launch {
            try {
                val page = api.feed(subreddit, sort, after)
                if (gen != generation) return@launch
                val seen = posts.mapTo(HashSet()) { it.name }
                posts.addAll(page.posts.filter { seen.add(it.name) })
                after = page.after
                endReached = page.after == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: "Something went wrong"
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun refresh() {
        generation++
        job?.cancel()
        posts.clear()
        after = null
        endReached = false
        loading = false
        error = null
        listState = LazyListState()
        loadMore()
    }

    fun changeSort(newSort: FeedSort) {
        if (newSort == sort) return
        sort = newSort
        refresh()
    }
}

/** A post and its comments. */
class ThreadScreen(
    val permalink: String,
    preview: Post?,
    private val api: RedditApi,
    private val scope: CoroutineScope,
) : Screen {
    var post by mutableStateOf(preview)
        private set
    val items = mutableStateListOf<ThreadItem>()
    var sort by mutableStateOf(CommentSort.Best)
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

    val webUrl: String get() = app.lurk.web.Links.webOrigin + permalink

    fun load() {
        generation++
        val gen = generation
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                val data = api.thread(permalink, sort)
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

    fun changeSort(newSort: CommentSort) {
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
                val loaded = api.moreChildren(post, more, sort)
                if (gen != generation) return@launch
                val index = items.indexOfFirst { it.key == more.key }
                if (index >= 0) {
                    items.removeAt(index)
                    // Rows must have unique keys; skip anything Reddit sends twice.
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

    /** The permalink for a "continue this thread" row. */
    fun continueUrl(more: More): String {
        // The post's own permalink never ends in a comment id, unlike a single-thread link.
        val base = (post?.permalink ?: permalink).substringBefore('?').trimEnd('/')
        return base + "/" + more.parentName.removePrefix("t1_") + "/"
    }

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

/** A page shown in the visible WebView: sign-in, inbox, user pages, anything without a native screen. */
class WebScreen(var url: String) : Screen

enum class Vote { Up, None, Down }

/**
 * Votes and saves, applied to the UI at once and sent through the engine. Overrides
 * are kept by fullname so every screen showing the same post or comment agrees.
 */
class Actions(
    private val api: RedditApi,
    private val scope: CoroutineScope,
    private val signedIn: () -> Boolean,
    private val onMessage: (String) -> Unit,
) {
    private val votes = mutableStateMapOf<String, Vote>()
    private val saves = mutableStateMapOf<String, Boolean>()

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
                api.vote(name, next.weight)
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
                api.setSaved(name, !before)
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
