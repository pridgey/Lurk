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
        if (community == null) learn(page)
        return page
    }

    /** Whether [community]'s saved posts are old enough to fetch again when coming back to the app. */
    fun isStale(community: String?) = rss.isStale(community)

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

        private const val NO_COMMENTS = "RSS has posts only. Open the post on Reddit for that."

        /** The Reddit username a front page feed link belongs to. */
        fun userOf(link: String): String? = Uri.parse(link).getQueryParameter("user")?.takeIf { it.isNotBlank() }
    }
}
