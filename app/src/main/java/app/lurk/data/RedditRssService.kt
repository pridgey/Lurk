package app.lurk.data

import android.content.Context
import android.net.Uri
import app.lurk.web.RedditLinks
import org.json.JSONObject

/**
 * A Reddit account read through RSS ([RedditRss]): Home is the front page's private feed,
 * subreddits are their public feeds. Its "sign-in" is that feed link, kept as the
 * account's token. Comments, votes and saves aren't in RSS; the screens open the post on
 * Reddit (in Chrome) for those.
 *
 * Reddit won't say which subreddits the account follows over RSS, so the bottom bar is
 * built from the front page: every subreddit its posts come from, dropped again after
 * [FORGET_AFTER_MS] without a post on it.
 */
class RedditRssService(
    context: Context,
    accountId: String,
    build: Int,
    link: String?,
    /** The subreddits learned from the front page changed. */
    private val onLearned: (List<String>) -> Unit,
    /** Reddit stopped accepting the feed link. */
    private val onLinkRejected: () -> Unit,
) : Service {

    override val log = DebugLog()
    private val rss = RedditRss(context, accountId, build, log).also { it.link = link }

    var link: String?
        get() = rss.link
        set(value) {
            rss.link = value
        }

    override val kind = ServiceKind.RedditRss
    override val naming = RedditNaming
    override val links = RedditLinks
    override val feedSorts = emptyList<Sort>()
    override val commentSorts = emptyList<Sort>()
    override val fixedFeeds = listOf(FixedFeed("popular", "Popular"), FixedFeed("all", "All"))
    override val signIn = SignIn.Form
    override val downvotes = false
    override val loadingNote: String? get() = rss.waiting

    override suspend fun whoAmI(): String? = link?.let(::userOf)

    override suspend fun subscriptions(): List<String> = learned.values.map { it.first }.sortedBy { it.lowercase() }

    override fun isMixed(community: String?): Boolean =
        community == null || community.lowercase() in setOf("all", "popular") || '+' in community

    override suspend fun feed(community: String?, sort: Sort?, cursor: String?): FeedPage =
        feed(community, sort, cursor, refresh = false)

    override suspend fun feed(community: String?, sort: Sort?, cursor: String?, refresh: Boolean): FeedPage {
        val page = try {
            rss.page(community, cursor, refresh)
        } catch (e: FeedLinkRejected) {
            onLinkRejected()
            throw e
        }
        if (community != null) return page
        learn(page)
        if (cursor != null) return page
        // Posts mixed in a while ago stay through a refresh, below the front page's first few.
        val keep = System.currentTimeMillis() - MIX_KEEP_MS
        mixed.removeAll { it.second < keep }
        return FeedPage(interleave(page.posts, mixed.map { it.first }, MIX_START, MIX_SPACING), page.after, page.fetchedAt, page.notice)
    }

    // --- Mixing subreddit posts into Home ---

    /** Posts mixed into Home, newest first, with when (ms). */
    private val mixed = ArrayList<Pair<Post, Long>>()
    /** Every post mixed in this session, so none comes back after it's scrolled past. */
    private val everMixed = HashSet<String>()
    /** Subreddits whose feed failed this session (private, banned). */
    private val skip = HashSet<String>()

    /**
     * A few recent posts from one learned subreddit that [shown] doesn't have, for Home.
     * Takes the subreddit fetched longest ago, and spends at most one subreddit-feed request,
     * only when Reddit's limit lets it go out at once. Empty when there's nothing to add.
     */
    suspend fun freshForHome(shown: Set<String>): List<Post> {
        if (link == null) return emptyList()
        val now = System.currentTimeMillis()
        val sub = learned.values.map { it.first }
            .filter { it.lowercase() !in skip }
            .minByOrNull { rss.savedAt(it) ?: 0L } ?: return emptyList()
        val fresh = now - (rss.savedAt(sub) ?: 0L) < RedditRss.FRESH_MS
        if (!fresh && !rss.subredditReady()) return emptyList()
        val page = try {
            rss.page(sub, null, force = false)
        } catch (e: NoSuchFeed) {
            skip += sub.lowercase()
            return emptyList()
        }
        val newest = now / 1000 - MIX_MAX_AGE_S
        val picked = page.posts
            .filter { it.createdUtc >= newest && it.name !in shown && it.name !in everMixed }
            .take(MIX_PER_SUB)
        picked.forEach { everMixed += it.name }
        mixed.addAll(0, picked.map { it to now })
        while (mixed.size > MIX_KEEP_MAX) mixed.removeAt(mixed.lastIndex)
        return picked
    }

    /** Whether [community]'s saved posts are old enough to fetch again when coming back to the app. */
    fun isStale(community: String?) = rss.isStale(community)

    /** Reddit's search feed, by title: one request (a subreddit-feed turn) for the last week's threads. */
    override suspend fun gameThreads(community: String, refresh: Boolean): FeedPage =
        try {
            rss.search(community, GameThreads.SEARCH, refresh)
        } catch (e: FeedLinkRejected) {
            onLinkRejected()
            throw e
        }

    override suspend fun thread(ref: String, sort: Sort?): ThreadData = throw ServiceException(NO_COMMENTS)
    override suspend fun moreChildren(post: Post, more: More, sort: Sort?): List<ThreadItem> = emptyList()
    override fun continueRef(post: Post?, threadRef: String, more: More): String = threadRef
    override suspend fun vote(name: String, dir: Int) = throw ServiceException(NO_COMMENTS)
    override suspend fun setSaved(name: String, saved: Boolean) = throw ServiceException(NO_COMMENTS)
    override val discoverSorts = emptyList<Sort>()
    override suspend fun discover(sort: Sort?, cursor: String?): CommunityPage = throw ServiceException(NO_COMMENTS)
    override suspend fun searchCommunities(query: String, cursor: String?): CommunityPage = throw ServiceException(NO_COMMENTS)
    override val peekSort: Sort? = null
    override suspend fun setSubscribed(community: String, subscribed: Boolean) = throw ServiceException(NO_COMMENTS)

    /** Signing out: forgets the link, the saved feeds and the learned subreddits. */
    fun logOut() {
        rss.link = null
        rss.clearCache()
        learned.clear()
        saveLearned()
        mixed.clear()
        everMixed.clear()
        skip.clear()
    }

    // --- Subreddits learned from the front page ---

    private val prefs = context.getSharedPreferences("reddit_rss", Context.MODE_PRIVATE)
    private val learnedKey = "subs:$accountId"

    /** Lowercase name -> (name as Reddit writes it, when a front page post was last seen from it). */
    private val learned: MutableMap<String, Pair<String, Long>> = runCatching {
        val o = JSONObject(prefs.getString(learnedKey, "{}") ?: "{}")
        o.keys().asSequence().associateTo(HashMap()) { k ->
            val v = o.getJSONObject(k)
            k to (v.getString("name") to v.getLong("seen"))
        }
    }.getOrDefault(HashMap())

    private fun learn(page: FeedPage) {
        val seen = page.fetchedAt ?: System.currentTimeMillis()
        val before = learned.keys.toSet()
        page.posts.mapNotNull { it.community }.forEach { name ->
            val k = name.lowercase()
            if (k == "all" || k == "popular" || name.startsWith("u_")) return@forEach
            val old = learned[k]?.second ?: 0L
            learned[k] = name to maxOf(old, seen)
        }
        learned.entries.removeAll { System.currentTimeMillis() - it.value.second > FORGET_AFTER_MS }
        saveLearned()
        if (learned.keys != before) onLearned(learned.values.map { it.first }.sortedBy { it.lowercase() })
    }

    private fun saveLearned() {
        val o = JSONObject()
        learned.forEach { (k, v) -> o.put(k, JSONObject().put("name", v.first).put("seen", v.second)) }
        prefs.edit().putString(learnedKey, o.toString()).apply()
    }

    companion object {
        /** A subreddit with no front page post in this long leaves the bar. */
        const val FORGET_AFTER_MS = 30L * 24 * 60 * 60_000

        /** How often Home takes posts from another subreddit while it's on screen. */
        const val MIX_EVERY_MS = 2 * 60_000L
        /** Posts taken from each subreddit per turn. */
        private const val MIX_PER_SUB = 3
        /** Only posts this new (s) are mixed in: older ones aren't news, and are often pinned. */
        private const val MIX_MAX_AGE_S = 24 * 60 * 60L
        /** Mixed posts kept through a Home refresh for this long, up to [MIX_KEEP_MAX]. */
        private const val MIX_KEEP_MS = 3 * 60 * 60_000L
        private const val MIX_KEEP_MAX = 30
        /** On a refresh, mixed posts go in from this position, one every [MIX_SPACING]. */
        private const val MIX_START = 4
        const val MIX_SPACING = 4

        private const val NO_COMMENTS = "RSS has posts only. Open the post on Reddit for that."

        /** The Reddit username a front page feed link belongs to. */
        fun userOf(link: String): String? = Uri.parse(link).getQueryParameter("user")?.takeIf { it.isNotBlank() }
    }
}
