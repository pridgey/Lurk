package app.lurk.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lurk.web.LemmyLinks
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object LemmySorts {
    val feed = listOf(
        Sort("Active", "Active"),
        Sort("Hot", "Hot"),
        Sort("Scaled", "Scaled"),
        Sort("New", "New"),
        Sort("TopDay", "Top today"),
        Sort("TopWeek", "Top this week"),
        Sort("TopMonth", "Top this month"),
        Sort("TopYear", "Top this year"),
        Sort("TopAll", "Top of all time"),
        Sort("MostComments", "Most comments"),
        Sort("Controversial", "Controversial"),
    )

    val comments = listOf(
        Sort("Hot", "Hot"),
        Sort("Top", "Top"),
        Sort("New", "New"),
        Sort("Old", "Old"),
        Sort("Controversial", "Controversial"),
    )
}

class LemmyNaming(private val instance: String) : Naming {
    override val communityNoun = "community"
    override fun community(name: String) = "c/$name"
    override fun user(name: String) = if ('@' in name) "@$name" else "@$name@$instance"

    /** Just the name, unless another subscription shares it. */
    override fun chip(name: String, all: List<String>): String {
        val short = name.substringBefore('@')
        return if (all.count { it.substringBefore('@').equals(short, ignoreCase = true) } > 1) name else short
    }

    override fun clean(typed: String) = typed.trim().removePrefix("/").removePrefix("c/").removePrefix("!")
    private val valid = Regex("[A-Za-z0-9_]{2,40}(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})?")
    override fun isCommunityName(name: String) = name.matches(valid)
}

/** A Lemmy sign-in that needs the account's two-factor code. */
class NeedsTwoFactor : Exception("Enter the code from your authenticator app")

/**
 * A Lemmy instance, through its open JSON API (v3, which Lemmy 0.19 serves and 1.0
 * still accepts). PieFed instances work too, through their copy of that API. Signed-in
 * requests carry the login token the instance issued; without one, the instance's
 * public posts can still be read.
 */
class LemmyService(
    val instance: String,
    /** The login token. Cleared when the instance rejects it. */
    @Volatile var token: String?,
    private val onTokenRejected: () -> Unit,
    /** Every community across Lemmy, for Discover; without it, Discover reads this instance's list. */
    private val index: CommunityIndex? = null,
) : Service {

    override val log = DebugLog()
    override val kind = ServiceKind.Lemmy
    override val naming = LemmyNaming(instance)
    override val links = LemmyLinks(instance)
    override val feedSorts: List<Sort>
        get() = if (pieFed) LemmySorts.feed.filter { it.key !in PIEFED_NO_FEED_SORT } else LemmySorts.feed
    override val commentSorts = LemmySorts.comments
    override val fixedFeeds = listOf(FixedFeed(LOCAL, "Local"), FixedFeed(ALL, "All"))
    override val signIn = SignIn.Form

    /** Instances can turn downvotes off; learned from the site info at sync. */
    override var downvotes by mutableStateOf(true)
        private set

    /** The instance runs PieFed, which lacks a few of Lemmy's sorts; learned at sync. */
    private var pieFed by mutableStateOf(false)

    private val client = LemmyClient(instance, log) { token }

    /** Subscriptions from the last [whoAmI], which the site info includes. */
    private var follows: List<String>? = null

    override var authNote: String? = null
        private set

    override suspend fun whoAmI(): String? {
        authNote = null
        pieFed = client.api() == LemmyApi.PieFed
        val site = try {
            client.get("/site")
        } catch (e: LemmyError) {
            if (token == null || !e.isAuth) throw e
            rejectToken()
            client.get("/site")
        }
        // PieFed keeps the setting on "site".
        (site.optJSONObject("site_view")?.optJSONObject("local_site") ?: site.optJSONObject("site"))?.let {
            downvotes = it.optBoolean("enable_downvotes", true)
        }
        val me = site.optJSONObject("my_user")
        if (me == null && token != null) rejectToken()
        val name = me?.optJSONObject("local_user_view")?.optJSONObject("person")?.optString("name")
            ?.takeIf { it.isNotBlank() }
        val list = me?.optJSONArray("follows") ?: JSONArray()
        follows = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.optJSONObject("community")?.let(LemmyParse::community)
        }
        log.note("Signed in as: ${name?.let { "$it@$instance" } ?: "nobody"}")
        return name
    }

    override suspend fun subscriptions(): List<String> {
        val names = follows ?: run {
            whoAmI()
            follows.orEmpty()
        }
        return names.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    override fun isMixed(community: String?): Boolean = community == null || community == LOCAL || community == ALL

    /**
     * A community from another instance only has the posts that reached this one, often
     * none. When this instance's copy runs out on its first page, the community's home
     * instance is asked instead, and its pages carry on from there.
     */
    override suspend fun feed(community: String?, sort: Sort?, cursor: String?): FeedPage {
        if (cursor != null && cursor.startsWith(HOME) && community != null) {
            return homeFeed(community, sort, cursor.removePrefix(HOME))
        }
        val remote = cursor == null && community != null && '@' in community
        val local = try {
            localFeed(community, sort, cursor)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!remote) throw e
            return tryHome(community!!, sort) ?: throw e
        }
        if (!remote || local.after != null) return local
        val home = tryHome(community!!, sort) ?: return local
        return if (home.posts.size > local.posts.size) home else local
    }

    /** The home instance's first page, or null if it can't be read (it's down, or isn't Lemmy). */
    private suspend fun tryHome(community: String, sort: Sort?): FeedPage? = try {
        homeFeed(community, sort, null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.note("  home instance unavailable: ${e.message}")
        null
    }

    /** "linux@lemmy.ml" read from lemmy.ml itself, signed out: this instance's token stays here. */
    private suspend fun homeFeed(community: String, sort: Sort?, cursor: String?): FeedPage {
        val host = community.substringAfter('@')
        val home = homeClients.getOrPut(host.lowercase()) { LemmyClient(host, log) { null } }
        val params = linkedMapOf(
            "sort" to feedSort(sort, home.api()),
            "limit" to PAGE_SIZE.toString(),
            "community_name" to community.substringBefore('@'),
        )
        val page = paging(params, cursor)
        val root = home.get("/post/list", params)
        val result = posts(root, page) { LemmyParse.post(it, self = instance) }
        return FeedPage(result.posts, result.after?.let { HOME + it })
    }

    private val homeClients = ConcurrentHashMap<String, LemmyClient>()

    /** [sort]'s key, or Hot where PieFed doesn't have it (picked before it was known to be PieFed). */
    private fun feedSort(sort: Sort?, api: LemmyApi): String {
        val key = (sort ?: LemmySorts.feed.first()).key
        return if (api == LemmyApi.PieFed && key in PIEFED_NO_FEED_SORT) "Hot" else key
    }

    /** Sets [params]' page from [cursor]; returns the page number when paging by number. */
    private fun paging(params: MutableMap<String, String>, cursor: String?): Int? {
        // Lemmy 0.19 pages by cursor; older instances by page number.
        val page = cursor?.removePrefix("page:")?.takeIf { cursor.startsWith("page:") }?.toInt()
        if (page != null) params["page"] = page.toString() else if (cursor != null) params["page_cursor"] = cursor
        return page
    }

    private fun posts(root: JSONObject, page: Int?, parse: (JSONObject) -> Post): FeedPage {
        val list = root.optJSONArray("posts") ?: JSONArray()
        val posts = (0 until list.length()).map { parse(list.getJSONObject(it)) }
        val next = when {
            posts.isEmpty() -> null
            root.has("next_page") -> root.optString("next_page").takeIf { !root.isNull("next_page") && it.isNotBlank() }
            posts.size < PAGE_SIZE -> null
            else -> "page:${(page ?: 1) + 1}"
        }
        return FeedPage(posts, next)
    }

    private suspend fun localFeed(community: String?, sort: Sort?, cursor: String?): FeedPage {
        val params = linkedMapOf(
            "sort" to feedSort(sort, client.api()),
            "limit" to PAGE_SIZE.toString(),
        )
        when (community) {
            null -> if (token != null) params["type_"] = "Subscribed"
            LOCAL -> params["type_"] = "Local"
            ALL -> params["type_"] = "All"
            else -> params["community_name"] = community
        }
        val page = paging(params, cursor)
        val root = try {
            client.get("/post/list", params)
        } catch (e: LemmyError) {
            // A community from another instance this one hasn't seen yet: fetch it, then retry.
            if (e.code != "couldnt_find_community" || community == null || '@' !in community) throw e
            client.get("/resolve_object", mapOf("q" to "!$community"))
            client.get("/post/list", params)
        }
        return posts(root, page) { LemmyParse.post(it) }
    }

    override suspend fun thread(ref: String, sort: Sort?): ThreadData {
        val sortKey = (sort ?: commentSorts.first()).key
        val (type, id) = resolveRef(ref)
        if (type == "post") {
            val post = LemmyParse.post(client.get("/post", mapOf("id" to id)).getJSONObject("post_view"))
            val comments = client.get(
                "/comment/list",
                mapOf("post_id" to id, "max_depth" to MAX_DEPTH, "sort" to sortKey, "type_" to "All"),
            ).optJSONArray("comments") ?: JSONArray()
            return ThreadData(post, LemmyParse.tree(comments, post.name, post.name, 0, post.author), isSingleThread = false)
        }
        // One comment and its replies, under its post.
        val cv = client.get("/comment", mapOf("id" to id)).getJSONObject("comment_view")
        val postId = cv.getJSONObject("comment").getLong("post_id").toString()
        val post = LemmyParse.post(client.get("/post", mapOf("id" to postId)).getJSONObject("post_view"))
        val comments = client.get(
            "/comment/list",
            mapOf("parent_id" to id, "max_depth" to MAX_DEPTH, "sort" to sortKey, "type_" to "All"),
        ).optJSONArray("comments") ?: JSONArray()
        val parent = LemmyParse.parentOf(cv, post.name)
        return ThreadData(post, LemmyParse.tree(comments, post.name, parent, 0, post.author), isSingleThread = true)
    }

    /** "post:1" or "comment:2" as a type and id; links to other instances are looked up here first. */
    private suspend fun resolveRef(ref: String): Pair<String, String> {
        if (ref.startsWith("post:")) return "post" to ref.removePrefix("post:")
        if (ref.startsWith("comment:")) return "comment" to ref.removePrefix("comment:")
        val found = try {
            client.get("/resolve_object", mapOf("q" to ref))
        } catch (e: LemmyError) {
            throw ServiceException(
                if (token == null) "Sign in to open posts from other instances." else "$instance couldn't find that post.",
            )
        }
        found.optJSONObject("comment")?.let { return "comment" to it.getJSONObject("comment").getLong("id").toString() }
        found.optJSONObject("post")?.let { return "post" to it.getJSONObject("post").getLong("id").toString() }
        throw ServiceException("That link isn't a post.")
    }

    /** A comment's replies past the depth limit; its id is [More.children]. */
    override suspend fun moreChildren(post: Post, more: More, sort: Sort?): List<ThreadItem> {
        val id = more.children.first()
        val comments = client.get(
            "/comment/list",
            mapOf(
                "parent_id" to id,
                "max_depth" to MAX_DEPTH,
                "sort" to (sort ?: commentSorts.first()).key,
                "type_" to "All",
            ),
        ).optJSONArray("comments") ?: JSONArray()
        return LemmyParse.tree(comments, post.name, "c$id", more.depth, post.author)
    }

    override fun continueRef(post: Post?, threadRef: String, more: More): String =
        "comment:" + more.parentName.removePrefix("c")

    override suspend fun vote(name: String, dir: Int) {
        requireSignedIn()
        val (type, id) = split(name)
        client.send("POST", "/$type/like", JSONObject().put("${type}_id", id).put("score", dir))
    }

    override suspend fun setSaved(name: String, saved: Boolean) {
        requireSignedIn()
        val (type, id) = split(name)
        client.send("PUT", "/$type/save", JSONObject().put("${type}_id", id).put("save", saved))
    }

    /**
     * Community list sorts. Lemmy ranks Top* by people active in that stretch (TopAll: by
     * members). Its Hot and Active sorts favor brand-new communities, so they aren't offered.
     */
    override val discoverSorts = listOf(
        Sort("TopMonth", "Active"),
        Sort("TopWeek", "This week"),
        Sort("TopAll", "Biggest"),
        Sort("New", "Newest"),
    )

    /** Every community the instance knows; pages by number. */
    override suspend fun discover(sort: Sort?, cursor: String?): CommunityPage {
        val page = cursor?.toIntOrNull() ?: 1
        val key = (sort ?: discoverSorts.first()).key
        val root = client.get(
            "/community/list",
            mapOf(
                "type_" to "All",
                "sort" to if (client.api() == LemmyApi.PieFed) PIEFED_DISCOVER_SORT[key] ?: key else key,
                "limit" to DISCOVER_PAGE.toString(),
                "page" to page.toString(),
            ),
        )
        val list = root.optJSONArray("communities") ?: JSONArray()
        val communities = (0 until list.length()).map { LemmyParse.communityInfo(list.getJSONObject(it)) }
        return CommunityPage(communities, if (list.length() < DISCOVER_PAGE) null else (page + 1).toString())
    }

    override val discoverTopics: List<Topic> = if (index != null) Topics.all else emptyList()

    /**
     * From the community index when it's at hand: it covers communities this instance
     * hasn't heard of, counts posts where they live, and knows topics. Otherwise this
     * instance's list, which a deck that started there keeps reading.
     */
    override suspend fun discover(sort: Sort?, cursor: String?, topic: Topic?): CommunityPage {
        if (index != null && (cursor == null || cursor.startsWith(INDEX))) {
            val all = if (topic != null) index.await() else index.cached()
            if (all != null) {
                val key = (sort ?: discoverSorts.first()).key
                val reachable = reachable()
                val ordered = withContext(Dispatchers.Default) {
                    val picked = all.filter { (topic == null || topic.key in it.topics) && reachable(it.host) }
                    when (key) {
                        "TopWeek" -> picked.sortedByDescending { it.activeWeek }
                        "TopAll" -> picked.sortedByDescending { it.subscribers }
                        "New" -> picked.sortedByDescending { it.published }
                        else -> picked.sortedByDescending { it.activeMonth }
                    }
                }
                return indexPage(ordered, cursor, DISCOVER_PAGE)
            }
            if (cursor != null) return CommunityPage(emptyList(), null)
            if (topic != null) {
                throw ServiceException(index.error?.let { "Topics need the community list from lemmyverse.net. $it." }
                    ?: "Topics need the community list from lemmyverse.net, which didn't load.")
            }
        }
        return discover(sort, cursor)
    }

    /** One page of [list], from the offset in [cursor]. */
    private fun indexPage(list: List<IndexedCommunity>, cursor: String?, size: Int): CommunityPage {
        val from = cursor?.removePrefix(INDEX)?.toIntOrNull() ?: 0
        val page = list.drop(from).take(size).map(::fromIndex)
        val next = (from + size).takeIf { it < list.size }?.let { INDEX + it }
        return CommunityPage(page, next)
    }

    private fun fromIndex(c: IndexedCommunity) = CommunityInfo(
        name = if (c.host.equals(instance, ignoreCase = true)) c.name else "${c.name}@${c.host}",
        title = c.title,
        description = c.description,
        subscribers = c.subscribers,
        activeUsers = c.activeWeek,
        icon = c.icon,
        banner = c.banner,
        over18 = c.nsfw,
        subscribed = false,
        posts = c.posts,
    )

    /** Hosts this instance won't talk to, learned once: its block list, or everything off its allow list. */
    @Volatile private var federation: Pair<Set<String>, Set<String>>? = null

    private suspend fun reachable(): (String) -> Boolean {
        val (allowed, blocked) = federation ?: try {
            val lists = client.get("/federated_instances").optJSONObject("federated_instances")
            fun hosts(key: String): Set<String> {
                val list = lists?.optJSONArray(key) ?: return emptySet()
                return (0 until list.length()).mapNotNullTo(HashSet()) { list.optJSONObject(it)?.optString("domain")?.lowercase() }
            }
            (hosts("allowed") to hosts("blocked")).also { federation = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet<String>() to emptySet<String>() // try again next time
        }
        val self = instance.lowercase()
        return { host ->
            host == self || (host !in blocked && (allowed.isEmpty() || host in allowed))
        }
    }

    /**
     * Searches the community index when it's at hand (names and titles first, then
     * descriptions, biggest first), else this instance. Also this instance when the index
     * has nothing, since it may know communities the index doesn't.
     */
    override suspend fun searchCommunities(query: String, cursor: String?): CommunityPage {
        if (index != null && (cursor == null || cursor.startsWith(INDEX))) {
            val all = index.cached()
            if (all != null) {
                val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
                val reachable = reachable()
                fun has(text: String) = terms.all { it in text }
                val found = withContext(Dispatchers.Default) {
                    val named = ArrayList<IndexedCommunity>()
                    val described = ArrayList<IndexedCommunity>()
                    for (c in all) {
                        if (!reachable(c.host)) continue
                        when {
                            has((c.name + " " + c.title.orEmpty()).lowercase()) -> named.add(c)
                            has(c.description.lowercase()) -> described.add(c)
                        }
                    }
                    named.sortedByDescending { it.subscribers } + described.sortedByDescending { it.subscribers }
                }
                if (found.isNotEmpty() || cursor != null) return indexPage(found, cursor, SEARCH_PAGE)
            } else if (cursor != null) {
                return CommunityPage(emptyList(), null)
            }
        }
        return searchInstance(query, cursor)
    }

    /** Every community the instance knows whose name or title matches; TopAll ranks by members. */
    private suspend fun searchInstance(query: String, cursor: String?): CommunityPage {
        val page = cursor?.toIntOrNull() ?: 1
        val root = client.get(
            "/search",
            mapOf(
                "q" to query,
                "type_" to "Communities",
                "listing_type" to "All",
                "sort" to "TopAll",
                "limit" to SEARCH_PAGE.toString(),
                "page" to page.toString(),
            ),
        )
        val list = root.optJSONArray("communities") ?: JSONArray()
        val communities = (0 until list.length()).map { LemmyParse.communityInfo(list.getJSONObject(it)) }
        return CommunityPage(communities, if (list.length() < SEARCH_PAGE) null else (page + 1).toString())
    }

    override val peekSort = LemmySorts.feed.first { it.key == "TopWeek" }

    override suspend fun setSubscribed(community: String, subscribed: Boolean) {
        requireSignedIn()
        val id = communityId(community)
        client.send("POST", "/community/follow", JSONObject().put("community_id", id).put("follow", subscribed))
        follows = null // the next sync asks again
    }

    /** A community's id on this instance, looking it up on its home instance if this one hasn't seen it. */
    private suspend fun communityId(name: String): Long {
        val found = try {
            client.get("/community", mapOf("name" to name))
        } catch (e: LemmyError) {
            if (e.code != "couldnt_find_community" || '@' !in name) throw e
            client.get("/resolve_object", mapOf("q" to "!$name"))
            client.get("/community", mapOf("name" to name))
        }
        return found.getJSONObject("community_view").getJSONObject("community").getLong("id")
    }

    /** Invalidates the token on the instance (Lemmy 0.19+); older ones just forget it. */
    suspend fun logOut() {
        if (token != null) client.send("POST", "/user/logout", JSONObject())
    }

    override fun forget() {
        follows = null
    }

    /** Post ids here for posts read from their home instance, by address. */
    private val resolved = ConcurrentHashMap<String, Long>()

    private suspend fun split(name: String): Pair<String, Long> = when (name.first()) {
        'r' -> "post" to localPostId(name.drop(1))
        'p' -> "post" to name.drop(1).toLong()
        'c' -> "comment" to name.drop(1).toLong()
        else -> throw ServiceException("Can't do that here")
    }

    /** A home instance's post, by address, as this instance knows it, fetching it here if need be. */
    private suspend fun localPostId(apId: String): Long {
        resolved[apId]?.let { return it }
        val found = client.get("/resolve_object", mapOf("q" to apId)).optJSONObject("post")
            ?: throw ServiceException("$instance couldn't find that post.")
        return found.getJSONObject("post").getLong("id").also { resolved[apId] = it }
    }

    private fun requireSignedIn() {
        if (token == null) throw ServiceException("Sign in first")
    }

    private fun rejectToken() {
        log.note("$instance no longer accepts the login token")
        authNote = "$instance no longer accepts this sign-in. Tokens end after a password change or when they expire."
        token = null
        onTokenRejected()
    }

    companion object {
        /** Fixed feeds: this instance's communities, and everything it knows. */
        const val LOCAL = ":local"
        const val ALL = ":all"
        /** Marks feed cursors for a community's home instance. */
        private const val HOME = "home:"
        /** Marks Discover cursors that read the community index. */
        private const val INDEX = "idx:"
        private const val PAGE_SIZE = 25
        private const val DISCOVER_PAGE = 50
        private const val SEARCH_PAGE = 30
        private const val MAX_DEPTH = "8"
        /** Lemmy post sorts PieFed doesn't offer. */
        private val PIEFED_NO_FEED_SORT = setOf("MostComments", "Controversial")
        /** PieFed's nearest community sorts to [discoverSorts]. */
        private val PIEFED_DISCOVER_SORT = mapOf("TopMonth" to "Active", "TopWeek" to "Hot", "TopAll" to "TopSubscribers")

        /** "https://Lemmy.World/c/x" or "lemmy.world" to "lemmy.world". */
        fun normalizeInstance(input: String): String =
            input.trim().lowercase().removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore('?').trimEnd('.')

        /** Signs in and returns the login token. Throws [NeedsTwoFactor] when a code is required. */
        suspend fun login(instance: String, username: String, password: String, totp: String?): String {
            val client = LemmyClient(instance, DebugLog()) { null }
            val body = JSONObject().put("username_or_email", username.trim()).put("password", password)
            if (client.api() == LemmyApi.PieFed) body.put("username", username.trim())
            if (!totp.isNullOrBlank()) body.put("totp_2fa_token", totp.trim())
            val result = try {
                client.send("POST", "/user/login", body)
            } catch (e: LemmyError) {
                when (e.code) {
                    "missing_totp_token" -> throw NeedsTwoFactor()
                    "incorrect_totp_token" -> throw ServiceException("That code didn't work. Try the current one.")
                    "incorrect_login", "password_incorrect", "couldnt_find_that_username_or_email" ->
                        throw ServiceException("Wrong username or password.")
                    "email_not_verified" -> throw ServiceException("Verify your email address first.")
                    "registration_application_is_pending", "registration_application_pending" ->
                        throw ServiceException("Your sign-up is still waiting for approval.")
                    else -> throw e
                }
            }
            return result.optString("jwt").takeIf { it.isNotBlank() }
                ?: throw ServiceException("$instance didn't sign you in. You may need to verify your email or wait for approval.")
        }
    }
}

/** An error the instance reported, e.g. {"error":"couldnt_find_community"}. */
class LemmyError(val status: Int, val code: String?, message: String) : ServiceException(message) {
    val isAuth: Boolean
        get() = status == 401 || code in setOf("not_logged_in", "incorrect_login", "invalid_auth_token")
}

/** Where an instance serves the Lemmy API: Lemmy itself, or PieFed's copy of it. */
enum class LemmyApi(val path: String) { Lemmy("/api/v3"), PieFed("/api/alpha") }

/** JSON requests to one instance's API: /api/v3, or /api/alpha on PieFed. */
class LemmyClient(
    private val instance: String,
    private val log: DebugLog,
    private val token: () -> String?,
) {
    /** Which API the instance serves, from its nodeinfo; asked once per host. */
    suspend fun api(): LemmyApi {
        val key = instance.lowercase()
        apis[key]?.let { return it }
        val software = withContext(Dispatchers.IO) { software() }
        val api = if (software == "piefed") LemmyApi.PieFed else LemmyApi.Lemmy
        // Unreachable: guess Lemmy for now and ask again next time.
        if (software != null) apis[key] = api
        if (api == LemmyApi.PieFed) log.note("$instance runs PieFed")
        return api
    }

    /** The software nodeinfo names, lowercase; "" when it names none; null when unreachable. */
    private fun software(): String? {
        val links = fetch("https://$instance/.well-known/nodeinfo") ?: return null
        val list = links.optJSONArray("links") ?: return ""
        val href = (list.length() - 1 downTo 0).firstNotNullOfOrNull { i ->
            list.optJSONObject(i)?.optString("href")?.takeIf { it.startsWith("https://") }
        } ?: return ""
        val info = fetch(href) ?: return null
        return info.optJSONObject("software")?.optString("name")?.lowercase().orEmpty()
    }

    /** A JSON document; {} when the reply isn't JSON; null when the host can't be reached. */
    private fun fetch(url: String): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = PROBE_MS
        conn.readTimeout = PROBE_MS
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Lurk (Android)")
        val text = if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
        runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    } catch (e: IOException) {
        null
    }

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val query = params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        return request("GET", path + if (query.isEmpty()) "" else "?$query", null)
    }

    suspend fun send(method: String, path: String, body: JSONObject): JSONObject = request(method, path, body)

    private suspend fun request(method: String, pathAndQuery: String, body: JSONObject?): JSONObject {
        val api = api()
        log.note("$method $instance${pathAndQuery.substringBefore('?')}")
        val (status, text) = withContext(Dispatchers.IO) {
            try {
                val conn = URL("https://$instance${api.path}$pathAndQuery").openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "Lurk (Android)")
                token()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } catch (e: IOException) {
                0 to (e.message ?: "")
            }
        }
        log.lastResult = text.take(4_000)
        if (status != 200) log.note("  -> HTTP $status")
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (status == 200 && json != null) return json

        val code = json?.optString("error")?.takeIf { it.isNotBlank() }
            ?: json?.let { pieFedCode(it) }.takeIf { api == LemmyApi.PieFed }
        val message = when {
            status == 0 -> "Couldn't reach $instance. Check your connection."
            json == null && status == 200 -> "$instance sent something that isn't Lemmy data."
            code == "couldnt_find_community" -> "Couldn't find that community."
            code == "couldnt_find_post" || status == 404 -> "Not found."
            code == "not_logged_in" -> "Sign in first."
            code == "rate_limit_error" || status == 429 -> "$instance is rate limiting. Wait a minute and try again."
            code != null -> "$instance said: ${code.replace('_', ' ')}"
            else -> "$instance returned an error ($status)."
        }
        throw LemmyError(status, code, message)
    }

    /** PieFed's {"code":400,"message":"incorrect_login"}, as Lemmy's error codes. */
    private fun pieFedCode(json: JSONObject): String? {
        val message = json.optString("message").takeIf { it.isNotBlank() } ?: return null
        return if ("unknown community" in message) "couldnt_find_community" else message
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TIMEOUT_MS = 20_000
        /** Shorter for nodeinfo, which an unreachable host is asked again on each request. */
        const val PROBE_MS = 8_000
        /** By lowercase host, for every client in the app. */
        val apis = ConcurrentHashMap<String, LemmyApi>()
    }
}
