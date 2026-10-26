package app.lurk.data

import android.content.Context
import android.webkit.WebView
import app.lurk.web.HttpResult
import app.lurk.web.RedditEngine
import app.lurk.web.RedditLinks
import app.lurk.web.Scripts
import app.lurk.web.WebProfiles
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

    override var authNote: String? = null
        private set

    override suspend fun whoAmI(): String? {
        engine.logCookies()
        authNote = null
        val result = send("/api/me.json", null)
        // Signed-out visitors get sent to the login page (old Reddit) or a 401.
        val name = if (sentToLogin(result) || result.status == 401) {
            null
        } else {
            check(result) // can't tell: no connection, rate limited, blocked...
            val data = parseJson(result) as? JSONObject
                ?: throw ServiceException("Reddit sent something unexpected")
            val me = data.optJSONObject("data")
            if (me?.optBoolean("is_suspended") == true) authNote = "Reddit has suspended this account."
            me?.optString("name")?.takeIf { it.isNotBlank() }
        }
        if (name == null) {
            authNote = if (WebProfiles.loginCookie(engine.webView) == null) {
                "Reddit's sign-in cookie is gone. Sign-ins expire after a while; signing in again fixes it."
            } else {
                "Reddit no longer accepts this sign-in. This happens after a password change, " +
                    "signing out everywhere, or when Reddit asks for a fresh sign-in."
            }
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

    /** Keys are Reddit's subreddit listings (/subreddits/popular, /subreddits/new). */
    override val discoverSorts = listOf(Sort("popular", "Popular"), Sort("new", "Newest"))

    override suspend fun discover(sort: Sort?, cursor: String?): CommunityPage {
        val listing = (sort ?: discoverSorts.first()).key
        return RedditParse.communities(
            getObject("/subreddits/$listing.json?raw_json=1&limit=100" + (cursor?.let { "&after=$it" } ?: "")),
        )
    }

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
        return parseJson(result)
    }

    private fun parseJson(result: HttpResult): Any =
        runCatching { org.json.JSONTokener(result.body).nextValue() }.getOrNull()
            ?.takeIf { it is JSONObject || it is JSONArray }
            ?: throw ServiceException("Reddit sent a web page instead of data. Try \"View on web\".")

    /** Sends a request to the current origin, moving to www if old Reddit turns it away. */
    private suspend fun send(path: String, form: Map<String, String>?): HttpResult {
        val result = engine.request(RedditLinks.webOrigin + path, form)
        if (result.status != 200) RedditPages.text(result.body)?.let { log.note("  page says: ${it.take(160)}") }
        val blocked = RedditPages.isBlock(result.body)
        if (!RedditLinks.usingOld || !(sentToLogin(result) || blocked)) return result

        log.note(
            if (blocked) "Old Reddit sent its block page; trying www.reddit.com"
            else "Old Reddit sent us to its login page; trying www.reddit.com",
        )
        val retry = engine.request(RedditLinks.WWW_ORIGIN + path, form)
        if (retry.status != 200) RedditPages.text(retry.body)?.let { log.note("  page says: ${it.take(160)}") }
        if (retry.status != 200 || sentToLogin(retry) || RedditPages.isBlock(retry.body)) return result
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
        if (RedditPages.isBlock(result.body)) {
            throw ServiceException(
                "Reddit is blocking this app's requests (\"You've been blocked by network security\"). " +
                    "Your sign-in is fine; Reddit is turning away this connection. Try another network " +
                    "(Wi-Fi vs mobile data), turn off any VPN or Private DNS, or wait a few hours.",
            )
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

/** Reddit's HTML pages, as they come back in place of data. */
object RedditPages {
    /**
     * Reddit's anti-bot pages. The "network security" one puts its message after
     * ~190 KB of inline CSS, so the whole page is searched.
     */
    fun isBlock(body: String): Boolean {
        if (!body.trimStart().startsWith("<")) return false // data, not a page
        return body.contains("blocked by network security", ignoreCase = true) ||
            body.contains("whoa there, pardner", ignoreCase = true)
    }

    /** A page's visible text, for the debug log. Null for data (JSON). */
    fun text(body: String): String? {
        if (!body.trimStart().startsWith("<")) return null
        return body
            .replace(Regex("<(script|style)\\b[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            .takeIf { it.isNotEmpty() }
    }
}
