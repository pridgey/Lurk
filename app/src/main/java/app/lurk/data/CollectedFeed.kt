package app.lurk.data

import android.content.Context
import android.util.AtomicFile
import app.lurk.web.RedditLinks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What the collected account is called. There's one, so sync never mistakes it for a duplicate. */
const val COLLECTED_NAME = "Collected"

/** The subreddits in [text], however they're separated or prefixed, each once. */
fun parseSubreddits(text: String): List<String> =
    text.split(Regex("[\\s,]+"))
        .map { it.trim().trimEnd('/').substringAfterLast("r/").removePrefix("/") }
        .filter { SUBREDDIT.matches(it) }
        .distinctBy { it.lowercase() }

private val SUBREDDIT = Regex("[A-Za-z0-9_]{2,21}")

/**
 * Posts collected in the background for the collected account, kept in app storage
 * (Android doesn't clear it the way it clears a cache), one file per account.
 */
class CollectedPosts private constructor(private val file: AtomicFile) {

    data class Entry(val post: Post, val firstSeen: Long)

    private var entries: List<Entry>? = null

    @Synchronized
    fun posts(): List<Post> = loaded().map { it.post }

    /** Adds [incoming], refreshing posts already kept, and drops what's past its time. */
    @Synchronized
    fun add(incoming: List<Post>, now: Long = System.currentTimeMillis()) {
        val merged = merge(loaded(), incoming, now)
        entries = merged
        write(merged)
    }

    /** Posts per subreddit (lowercase) from the last day: how busy each one is. */
    @Synchronized
    fun recentCounts(now: Long = System.currentTimeMillis()): Map<String, Int> {
        val since = now / 1000 - DAY_S
        return loaded().filter { it.post.createdUtc >= since }
            .groupingBy { it.post.community.orEmpty().lowercase() }
            .eachCount()
    }

    @Synchronized
    fun clear() {
        entries = emptyList()
        file.delete()
    }

    private fun loaded(): List<Entry> = entries ?: read().also { entries = it }

    private fun read(): List<Entry> = runCatching {
        val arr = JSONArray(String(file.readFully()))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(PostCodec.decode(o.getJSONObject("post")), o.getLong("firstSeen"))
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("post", PostCodec.encode(it.post)).put("firstSeen", it.firstSeen)) }
        val out = file.startWrite()
        try {
            out.write(arr.toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    companion object {
        const val KEEP_MS = 14L * 24 * 60 * 60_000
        const val MAX_POSTS = 2_000
        private const val DAY_S = 24 * 60 * 60L

        private val open = HashMap<String, CollectedPosts>()

        @Synchronized
        fun get(context: Context, accountId: String): CollectedPosts = open.getOrPut(accountId) {
            val dir = File(context.applicationContext.filesDir, "collected").apply { mkdirs() }
            CollectedPosts(AtomicFile(File(dir, "$accountId.json")))
        }

        /** Newest first, each post once, keeping when it was first collected. */
        fun merge(old: List<Entry>, incoming: List<Post>, now: Long): List<Entry> {
            val byName = LinkedHashMap<String, Entry>()
            old.forEach { byName[it.post.name] = it }
            incoming.forEach { p -> byName[p.name] = Entry(p, byName[p.name]?.firstSeen ?: now) }
            return byName.values
                .filter { now - it.firstSeen <= KEEP_MS }
                .sortedWith(NEWEST)
                .take(MAX_POSTS)
        }

        private val NEWEST = compareByDescending<Entry> { it.post.createdUtc }.thenBy { it.post.name }

        /**
         * Up to [size] of [posts] (sorted as [merge] sorts them) after [cursor]. The cursor is
         * a place in that order rather than an index, so posts arriving mid-scroll don't
         * repeat any.
         */
        fun pageAfter(posts: List<Post>, cursor: String?, size: Int): Pair<List<Post>, String?> {
            val time = cursor?.substringBefore(':')?.toLongOrNull()
            val name = cursor?.substringAfter(':', "")
            val rest = if (time == null) posts else posts.dropWhile {
                it.createdUtc > time || (it.createdUtc == time && it.name <= name.orEmpty())
            }
            val page = rest.take(size)
            val next = page.lastOrNull()?.takeIf { rest.size > size }?.let { "${it.createdUtc}:${it.name}" }
            return page to next
        }
    }
}

/**
 * Reddit with no Reddit sign-in: a list of subreddits kept on the phone, read only from
 * [CollectedPosts]. Opening a feed never contacts Reddit; [LocalFeedSync] fills the store
 * in the background. Comments and votes open in Chrome, as on RSS accounts.
 */
class RedditLocalService(
    context: Context,
    accountId: String,
    /** The account's subreddits as they are now. */
    private val followed: () -> List<String>,
) : Service {

    private val appContext = context.applicationContext
    private val posts = CollectedPosts.get(appContext, accountId)

    override val log = DebugLog()
    override val kind = ServiceKind.RedditLocal
    override val naming = RedditNaming
    override val links = RedditLinks
    override val feedSorts = emptyList<Sort>()
    override val commentSorts = emptyList<Sort>()
    override val fixedFeeds = emptyList<FixedFeed>()
    override val signIn = SignIn.Form
    override val downvotes = false

    override suspend fun whoAmI(): String = COLLECTED_NAME

    override suspend fun subscriptions(): List<String> = followed().sortedBy { it.lowercase() }

    override fun isMixed(community: String?): Boolean = community == null || '+' in community

    override suspend fun feed(community: String?, sort: Sort?, cursor: String?): FeedPage = withContext(Dispatchers.IO) {
        val wanted = (community?.let(::listOf) ?: followed()).mapTo(HashSet()) { it.lowercase() }
        val matching = posts.posts().filter { it.community?.lowercase() in wanted }
        val (page, next) = CollectedPosts.pageAfter(matching, cursor, PAGE)
        FeedPage(
            page, next,
            fetchedAt = LocalFeedSync.lastCollected(appContext),
            notice = if (cursor == null) LocalFeedSync.notice(appContext, matching.isEmpty()) else null,
        )
    }

    override suspend fun thread(ref: String, sort: Sort?): ThreadData = throw ServiceException(NOT_HERE)
    override suspend fun moreChildren(post: Post, more: More, sort: Sort?): List<ThreadItem> = emptyList()
    override fun continueRef(post: Post?, threadRef: String, more: More): String = threadRef
    override suspend fun vote(name: String, dir: Int) = throw ServiceException(NOT_HERE)
    override suspend fun setSaved(name: String, saved: Boolean) = throw ServiceException(NOT_HERE)
    override val discoverSorts = emptyList<Sort>()
    override suspend fun discover(sort: Sort?, cursor: String?): CommunityPage = throw ServiceException(NOT_HERE)
    override suspend fun searchCommunities(query: String, cursor: String?): CommunityPage = throw ServiceException(NOT_HERE)
    override val peekSort: Sort? = null

    /** The list lives on the phone, and the session has already updated it. */
    override suspend fun setSubscribed(community: String, subscribed: Boolean) = LocalFeedSync.collectSoon(appContext)

    /** Removing the account: its posts go too. */
    fun logOut() = posts.clear()

    private companion object {
        const val PAGE = 50
        const val NOT_HERE = "Collected posts come from RSS. Open the post on Reddit for that."
    }
}
