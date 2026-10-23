package app.lurk.data

import android.content.Context
import android.webkit.WebView
import app.lurk.web.HttpResult
import app.lurk.web.RedditEngine
import app.lurk.web.RedditLinks
import app.lurk.web.Scripts
import org.json.JSONArray
import org.json.JSONObject

/** Reddit's sort orders. Keys are the listing path, plus ":time" for top. */
object RedditSorts {
    val feed = listOf(
        Sort("hot", "Hot"),
        Sort("new", "New"),
        Sort("rising", "Rising"),
        Sort("top:day", "Top today"),
        Sort("top:week", "Top this week"),
        Sort("top:month", "Top this month"),
        Sort("top:year", "Top this year"),
        Sort("top:all", "Top of all time"),
    )

    val comments = listOf(
        Sort("confidence", "Best"),
        Sort("top", "Top"),
        Sort("new", "New"),
        Sort("controversial", "Controversial"),
        Sort("old", "Old"),
        Sort("qa", "Q&A"),
    )

    /** The sort for a listing path ("top") and ?t= value, defaulting to Hot. */
    fun of(path: String?, time: String?): Sort {
        val key = if (path == "top") "top:${time ?: "day"}" else path
        return feed.firstOrNull { it.key == key } ?: feed.first()
    }

    /** A sort's listing path and its ?t= value, if any. */
    fun split(sort: Sort?): Pair<String, String?> {
        val key = sort?.key ?: feed.first().key
        return key.substringBefore(':') to key.substringAfter(':', "").ifEmpty { null }
    }
}

object RedditNaming : Naming {
    override val communityNoun = "subreddit"
    override fun community(name: String) = "r/$name"
    override fun user(name: String) = "u/$name"
    override fun clean(typed: String) = typed.trim().removePrefix("/").removePrefix("r/")
    private val valid = Regex("[A-Za-z0-9_]{2,21}")
    override fun isCommunityName(name: String) = name.matches(valid)
}

/**
 * Reddit's JSON versions of its own pages (append .json), requested by the hidden
 * engine with the account's cookies. Votes and saves are the same form posts the
 * old Reddit site makes.
 *
 * Starts on old Reddit. If old Reddit sends a request to its login page (it does this
 * even for some signed-in accounts), the request is retried on www.reddit.com, and if
 * that works, www is used from then on, for data and for web pages ([RedditLinks.webOrigin]).
 */
class RedditService(context: Context, profile: String?, scripts: Scripts) : Service {

    override val log = DebugLog()
    val engine = RedditEngine(context, profile, scripts, log)

    override val kind = ServiceKind.Reddit
    override val naming = RedditNaming
    override val links = RedditLinks
    override val feedSorts = RedditSorts.feed
    override val commentSorts = RedditSorts.comments
    override val fixedFeeds = listOf(FixedFeed("popular", "Popular"), FixedFeed("all", "All"))
    override val signIn = SignIn.Web(RedditLinks.LOGIN)
    override val downvotes = true
    override val engineView: WebView get() = engine.webView

    /** The account's anti-CSRF token, needed for votes and saves. Fetched on first use. */
    private var modhash: String? = null

    init {
        RedditLinks.webOrigin = RedditLinks.OLD_ORIGIN
    }

    /** Keeps the origin, which the sign-in check just chose. */
    override fun forget() {
        modhash = null
    }

    override suspend fun whoAmI(): String? {
        engine.logCookies()
        val name = try {
            getObject("/api/me.json").optJSONObject("data")?.optString("name")?.takeIf { it.isNotBlank() }
        } catch (e: ServiceException) {
            null // signed-out visitors get sent to the login page
        }
        log.note("Signed in as: ${name?.let { "u/$it" } ?: "nobody"} (via ${RedditLinks.webOrigin.removePrefix("https://")})")
        return name
    }

    override suspend fun subscriptions(): List<String> {
        val names = linkedMapOf<String, String>()
        var after: String? = null
        var pages = 0
        do {
            val page = getObject(
                "/subreddits/mine/subscriber.json?raw_json=1&limit=100" + (after?.let { "&after=$it" } ?: ""),
            )
            val data = page.optJSONObject("data") ?: break
            val children = data.optJSONArray("children") ?: JSONArray()
            for (i in 0 until children.length()) {
                val name = children.getJSONObject(i).optJSONObject("data")?.optString("display_name").orEmpty()
                // Followed users show up as "u_name" subreddits; leave those out.
                if (name.isNotBlank() && !name.startsWith("u_")) names.putIfAbsent(name.lowercase(), name)
            }
            after = data.optString("after").takeIf { !data.isNull("after") && it.isNotBlank() }
            pages++
        } while (after != null && pages < 10)
        return names.values.sortedBy { it.lowercase() }
    }

    /** Front page, r/all, r/popular and multireddits mix subreddits. */
    override fun isMixed(community: String?): Boolean =
        community == null || community.lowercase() in setOf("all", "popular") || '+' in community

    override suspend fun feed(community: String?, sort: Sort?, cursor: String?): FeedPage {
        val (listing, time) = RedditSorts.split(sort)
        val path = (community?.let { "/r/$it" } ?: "") + "/" + listing + ".json"
        val query = buildList {
            add("raw_json=1")
            add("limit=25")
            time?.let { add("t=$it") }
            cursor?.let { add("after=$it") }
        }.joinToString("&")
        return RedditParse.feed(getObject("$path?$query"))
    }

    override suspend fun thread(ref: String, sort: Sort?): ThreadData {
        val path = ref.substringBefore('?').substringBefore('#').trimEnd('/')
        val raw = getJson("$path.json?raw_json=1&limit=200&sort=${sort?.key ?: commentSorts.first().key}")
        val root = raw as? JSONArray ?: throw ServiceException("Reddit didn't send a thread")
        runCatching { RedditParse.imageDiagnostics(root) }.getOrNull()?.let(log::note)
        return RedditParse.thread(root, path)
    }

    /** Loads up to 100 of [more]'s comments. Any left over come back as a new [More] at the end. */
    override suspend fun moreChildren(post: Post, more: More, sort: Sort?): List<ThreadItem> {
        val batch = more.children.take(100)
        val root = getObject(
            "/api/morechildren.json?api_type=json&raw_json=1&limit_children=false" +
                "&link_id=${post.name}&sort=${sort?.key ?: commentSorts.first().key}&children=${batch.joinToString(",")}",
        )
        val items = RedditParse.moreChildren(root, more, post.author)
        val rest = more.children.drop(batch.size)
        return if (rest.isEmpty()) items else items + more.copy(children = rest, count = more.count - batch.size)
    }

    /** "Continue this thread": the parent comment's own permalink. */
    override fun continueRef(post: Post?, threadRef: String, more: More): String {
        // The post's own permalink never ends in a comment id, unlike a single-thread link.
        val base = (post?.threadRef ?: threadRef).substringBefore('?').trimEnd('/')
        return base + "/" + more.parentName.removePrefix("t1_") + "/"
    }

    override suspend fun vote(name: String, dir: Int) =
        post("/api/vote", mapOf("id" to name, "dir" to dir.toString()))

    override suspend fun setSaved(name: String, saved: Boolean) =
        post(if (saved) "/api/save" else "/api/unsave", mapOf("id" to name))

    override suspend fun discover(cursor: String?): CommunityPage =
        RedditParse.communities(
            getObject("/subreddits/popular.json?raw_json=1&limit=100" + (cursor?.let { "&after=$it" } ?: "")),
        )

    override val peekSort = RedditSorts.feed.first { it.key == "top:week" }

    override suspend fun setSubscribed(community: String, subscribed: Boolean) =
        post("/api/subscribe", mapOf("action" to if (subscribed) "sub" else "unsub", "sr_name" to community))

    override fun destroy() = engine.destroy()

    private suspend fun post(path: String, form: Map<String, String>) {
        val token = modhash ?: fetchModhash().also { modhash = it }
        val result = send(path, form + ("uh" to token))
        if (result.status == 403) modhash = null // token may have rotated; refetch next time
        check(result)
    }

    private suspend fun fetchModhash(): String {
        val me = getObject("/api/me.json")
        return me.optJSONObject("data")?.optString("modhash")?.takeIf { it.isNotBlank() }
            ?: throw ServiceException("Sign in first")
    }

    private suspend fun getObject(pathAndQuery: String): JSONObject =
        getJson(pathAndQuery) as? JSONObject ?: throw ServiceException("Reddit sent something unexpected")

    private suspend fun getJson(pathAndQuery: String): Any {
        val result = send(pathAndQuery, null)
        check(result)
        return runCatching { org.json.JSONTokener(result.body).nextValue() }.getOrNull()
            ?.takeIf { it is JSONObject || it is JSONArray }
            ?: throw ServiceException("Reddit sent a web page instead of data. Try \"View on web\".")
    }

    /** Sends a request to the current origin, moving to www if old Reddit turns it away. */
    private suspend fun send(path: String, form: Map<String, String>?): HttpResult {
        val result = engine.request(RedditLinks.webOrigin + path, form)
        if (!RedditLinks.usingOld || !sentToLogin(result)) return result

        log.note("Old Reddit sent us to its login page; trying www.reddit.com")
        val retry = engine.request(RedditLinks.WWW_ORIGIN + path, form)
        if (retry.status != 200 || sentToLogin(retry)) return result
        log.note("www.reddit.com works; using it from now on")
        RedditLinks.webOrigin = RedditLinks.WWW_ORIGIN
        return retry
    }

    private fun sentToLogin(result: HttpResult): Boolean =
        android.net.Uri.parse(result.url).path.orEmpty().startsWith("/login")

    private fun check(result: HttpResult) {
        // Reddit sends signed-out visitors it doesn't trust to the login page instead.
        if (sentToLogin(result)) {
            throw ServiceException("Reddit wants you to sign in before browsing. Use the account menu to sign in.")
        }
        when (result.status) {
            200 -> Unit
            0 -> throw ServiceException("Couldn't reach Reddit. Check your connection.")
            401, 403 -> throw ServiceException("Reddit refused the request. It may be private, or you may need to sign in.")
            404 -> throw ServiceException("Not found.")
            429 -> throw ServiceException("Reddit is rate limiting. Wait a minute and try again.")
            else -> throw ServiceException("Reddit returned an error (${result.status}).")
        }
    }
}
