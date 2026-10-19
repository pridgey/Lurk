package app.lurk.data

import app.lurk.web.HttpResult
import app.lurk.web.Links
import app.lurk.web.RedditEngine
import org.json.JSONArray
import org.json.JSONObject

enum class FeedSort(val label: String, val path: String, val time: String? = null) {
    Hot("Hot", "hot"),
    New("New", "new"),
    Rising("Rising", "rising"),
    TopDay("Top today", "top", "day"),
    TopWeek("Top this week", "top", "week"),
    TopMonth("Top this month", "top", "month"),
    TopYear("Top this year", "top", "year"),
    TopAll("Top of all time", "top", "all");

    companion object {
        fun of(path: String?, time: String?): FeedSort =
            entries.firstOrNull { it.path == path && (it.time == null || it.time == (time ?: "day")) } ?: Hot
    }
}

enum class CommentSort(val label: String, val param: String) {
    Best("Best", "confidence"),
    Top("Top", "top"),
    New("New", "new"),
    Controversial("Controversial", "controversial"),
    Old("Old", "old"),
    QA("Q&A", "qa"),
}

class RedditException(message: String) : Exception(message)

/**
 * Reddit's JSON versions of its own pages (append .json), requested by the hidden
 * engine with the account's cookies. Votes and saves are the same form posts the
 * old Reddit site makes.
 *
 * Starts on old Reddit. If old Reddit sends a request to its login page (it does this
 * even for some signed-in accounts), the request is retried on www.reddit.com, and if
 * that works, www is used from then on, for data and for web pages ([Links.webOrigin]).
 */
class RedditApi(private val engine: RedditEngine) {

    /** The account's anti-CSRF token, needed for votes and saves. Fetched on first use. */
    private var modhash: String? = null

    init {
        Links.webOrigin = Links.OLD_ORIGIN
    }

    /** Call when the signed-in account changes. Keeps the origin, which the sign-in check just chose. */
    fun forget() {
        modhash = null
    }

    /** The signed-in username, or null when signed out. */
    suspend fun whoAmI(): String? {
        val name = try {
            getObject("/api/me.json").optJSONObject("data")?.optString("name")?.takeIf { it.isNotBlank() }
        } catch (e: RedditException) {
            null // signed-out visitors get sent to the login page
        }
        engine.note("Signed in as: ${name?.let { "u/$it" } ?: "nobody"} (via ${Links.webOrigin.removePrefix("https://")})")
        return name
    }

    /** The account's subscribed subreddits, alphabetical. */
    suspend fun subscriptions(): List<String> {
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

    fun feedUrl(subreddit: String?, sort: FeedSort): String {
        val base = if (subreddit == null) Links.HOME else Links.subreddit(subreddit)
        return base + sort.path + "/" + (sort.time?.let { "?t=$it" } ?: "")
    }

    suspend fun feed(subreddit: String?, sort: FeedSort, after: String?): FeedPage {
        val path = (subreddit?.let { "/r/$it" } ?: "") + "/" + sort.path + ".json"
        val query = buildList {
            add("raw_json=1")
            add("limit=25")
            sort.time?.let { add("t=$it") }
            after?.let { add("after=$it") }
        }.joinToString("&")
        return Parse.feed(getObject("$path?$query"))
    }

    suspend fun thread(permalink: String, sort: CommentSort): ThreadData {
        val path = permalink.substringBefore('?').substringBefore('#').trimEnd('/')
        val raw = getJson("$path.json?raw_json=1&limit=200&sort=${sort.param}")
        val root = raw as? JSONArray ?: throw RedditException("Reddit didn't send a thread")
        return Parse.thread(root, path)
    }

    /** Loads up to 100 of [more]'s comments. Any left over come back as a new [More] at the end. */
    suspend fun moreChildren(post: Post, more: More, sort: CommentSort): List<ThreadItem> {
        val batch = more.children.take(100)
        val root = getObject(
            "/api/morechildren.json?api_type=json&raw_json=1&limit_children=false" +
                "&link_id=${post.name}&sort=${sort.param}&children=${batch.joinToString(",")}",
        )
        val items = Parse.moreChildren(root, more, post.author)
        val rest = more.children.drop(batch.size)
        return if (rest.isEmpty()) items else items + more.copy(children = rest, count = more.count - batch.size)
    }

    /** dir: 1 up, 0 clear, -1 down. */
    suspend fun vote(name: String, dir: Int) =
        post("/api/vote", mapOf("id" to name, "dir" to dir.toString()))

    suspend fun setSaved(name: String, saved: Boolean) =
        post(if (saved) "/api/save" else "/api/unsave", mapOf("id" to name))

    private suspend fun post(path: String, form: Map<String, String>) {
        val token = modhash ?: fetchModhash().also { modhash = it }
        val result = send(path, form + ("uh" to token))
        if (result.status == 403) modhash = null // token may have rotated; refetch next time
        check(result)
    }

    private suspend fun fetchModhash(): String {
        val me = getObject("/api/me.json")
        return me.optJSONObject("data")?.optString("modhash")?.takeIf { it.isNotBlank() }
            ?: throw RedditException("Sign in first")
    }

    private suspend fun getObject(pathAndQuery: String): JSONObject =
        getJson(pathAndQuery) as? JSONObject ?: throw RedditException("Reddit sent something unexpected")

    private suspend fun getJson(pathAndQuery: String): Any {
        val result = send(pathAndQuery, null)
        check(result)
        return runCatching { org.json.JSONTokener(result.body).nextValue() }.getOrNull()
            ?.takeIf { it is JSONObject || it is JSONArray }
            ?: throw RedditException("Reddit sent a web page instead of data. Try \"View on web\".")
    }

    /** Sends a request to the current origin, moving to www if old Reddit turns it away. */
    private suspend fun send(path: String, form: Map<String, String>?): HttpResult {
        val result = engine.request(Links.webOrigin + path, form)
        if (!Links.usingOld || !sentToLogin(result)) return result

        engine.note("Old Reddit sent us to its login page; trying www.reddit.com")
        val retry = engine.request(Links.WWW_ORIGIN + path, form)
        if (retry.status != 200 || sentToLogin(retry)) return result
        engine.note("www.reddit.com works; using it from now on")
        Links.webOrigin = Links.WWW_ORIGIN
        return retry
    }

    private fun sentToLogin(result: HttpResult): Boolean =
        android.net.Uri.parse(result.url).path.orEmpty().startsWith("/login")

    private fun check(result: HttpResult) {
        // Reddit sends signed-out visitors it doesn't trust to the login page instead.
        if (sentToLogin(result)) {
            throw RedditException("Reddit wants you to sign in before browsing. Use the account menu to sign in.")
        }
        when (result.status) {
            200 -> Unit
            0 -> throw RedditException("Couldn't reach Reddit. Check your connection.")
            401, 403 -> throw RedditException("Reddit refused the request. It may be private, or you may need to sign in.")
            404 -> throw RedditException("Not found.")
            429 -> throw RedditException("Reddit is rate limiting. Wait a minute and try again.")
            else -> throw RedditException("Reddit returned an error (${result.status}).")
        }
    }
}
