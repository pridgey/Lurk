package app.lurk.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lurk.web.LemmyLinks
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
 * still accepts). Signed-in requests carry the login token the instance issued;
 * without one, the instance's public posts can still be read.
 */
class LemmyService(
    val instance: String,
    /** The login token. Cleared when the instance rejects it. */
    @Volatile var token: String?,
    private val onTokenRejected: () -> Unit,
) : Service {

    override val log = DebugLog()
    override val kind = ServiceKind.Lemmy
    override val naming = LemmyNaming(instance)
    override val links = LemmyLinks(instance)
    override val feedSorts = LemmySorts.feed
    override val commentSorts = LemmySorts.comments
    override val fixedFeeds = listOf(FixedFeed(LOCAL, "Local"), FixedFeed(ALL, "All"))
    override val signIn = SignIn.Form

    /** Instances can turn downvotes off; learned from the site info at sync. */
    override var downvotes by mutableStateOf(true)
        private set

    private val client = LemmyClient(instance, log) { token }

    /** Subscriptions from the last [whoAmI], which the site info includes. */
    private var follows: List<String>? = null

    override suspend fun whoAmI(): String? {
        val site = try {
            client.get("/site")
        } catch (e: LemmyError) {
            if (token == null || !e.isAuth) throw e
            rejectToken()
            client.get("/site")
        }
        site.optJSONObject("site_view")?.optJSONObject("local_site")?.let {
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

    override suspend fun feed(community: String?, sort: Sort?, cursor: String?): FeedPage {
        val params = linkedMapOf(
            "sort" to (sort ?: feedSorts.first()).key,
            "limit" to PAGE_SIZE.toString(),
        )
        when (community) {
            null -> if (token != null) params["type_"] = "Subscribed"
            LOCAL -> params["type_"] = "Local"
            ALL -> params["type_"] = "All"
            else -> params["community_name"] = community
        }
        // Lemmy 0.19 pages by cursor; older instances by page number.
        val page = cursor?.removePrefix("page:")?.takeIf { cursor.startsWith("page:") }?.toInt()
        if (page != null) params["page"] = page.toString() else if (cursor != null) params["page_cursor"] = cursor

        val root = try {
            client.get("/post/list", params)
        } catch (e: LemmyError) {
            // A community from another instance this one hasn't seen yet: fetch it, then retry.
            if (e.code != "couldnt_find_community" || community == null || '@' !in community) throw e
            client.get("/resolve_object", mapOf("q" to "!$community"))
            client.get("/post/list", params)
        }
        val list = root.optJSONArray("posts") ?: JSONArray()
        val posts = (0 until list.length()).map { LemmyParse.post(list.getJSONObject(it)) }
        val next = when {
            posts.isEmpty() -> null
            root.has("next_page") -> root.optString("next_page").takeIf { !root.isNull("next_page") && it.isNotBlank() }
            posts.size < PAGE_SIZE -> null
            else -> "page:${(page ?: 1) + 1}"
        }
        return FeedPage(posts, next)
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

    private fun split(name: String): Pair<String, Long> = when (name.first()) {
        'p' -> "post" to name.drop(1).toLong()
        'c' -> "comment" to name.drop(1).toLong()
        else -> throw ServiceException("Can't do that here")
    }

    private fun requireSignedIn() {
        if (token == null) throw ServiceException("Sign in first")
    }

    private fun rejectToken() {
        log.note("$instance no longer accepts the login token")
        token = null
        onTokenRejected()
    }

    companion object {
        /** Fixed feeds: this instance's communities, and everything it knows. */
        const val LOCAL = ":local"
        const val ALL = ":all"
        private const val PAGE_SIZE = 25
        private const val MAX_DEPTH = "8"

        /** "https://Lemmy.World/c/x" or "lemmy.world" to "lemmy.world". */
        fun normalizeInstance(input: String): String =
            input.trim().lowercase().removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore('?').trimEnd('.')

        /** Signs in and returns the login token. Throws [NeedsTwoFactor] when a code is required. */
        suspend fun login(instance: String, username: String, password: String, totp: String?): String {
            val client = LemmyClient(instance, DebugLog()) { null }
            val body = JSONObject().put("username_or_email", username.trim()).put("password", password)
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

/** JSON requests to one instance's /api/v3. */
class LemmyClient(
    private val instance: String,
    private val log: DebugLog,
    private val token: () -> String?,
) {
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val query = params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        return request("GET", path + if (query.isEmpty()) "" else "?$query", null)
    }

    suspend fun send(method: String, path: String, body: JSONObject): JSONObject = request(method, path, body)

    private suspend fun request(method: String, pathAndQuery: String, body: JSONObject?): JSONObject {
        log.note("$method $instance${pathAndQuery.substringBefore('?')}")
        val (status, text) = withContext(Dispatchers.IO) {
            try {
                val conn = URL("https://$instance/api/v3$pathAndQuery").openConnection() as HttpURLConnection
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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TIMEOUT_MS = 20_000
    }
}
