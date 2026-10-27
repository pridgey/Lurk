package app.lurk.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import app.lurk.web.BlueskyLinks
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bluesky has no communities, so feeds stand in for them: a "community" is a feed's
 * at:// URI (a feed generator or a list). URIs make poor labels, so this keeps each
 * feed's display name as the service learns it, saved so the bar has names on launch.
 */
class BlueskyNaming(context: Context) : Naming {
    private val prefs = context.getSharedPreferences("bluesky_feeds", Context.MODE_PRIVATE)
    private val names = mutableStateMapOf<String, String>().apply {
        prefs.all.forEach { (k, v) -> if (v is String) put(k, v) }
    }

    override val communityNoun = "feed"
    override val memberNoun = "likes"
    override fun community(name: String) = names[name] ?: fallback(name)
    override fun label(name: String) = community(name)
    override fun chip(name: String, all: List<String>) = community(name)
    override fun user(name: String) = "@$name"
    override fun clean(typed: String) = typed.trim()
    /** Only a feed's at:// URI can be gone to; names are searched in Discover. */
    override fun isCommunityName(name: String) =
        name.startsWith("at://") && ("/app.bsky.feed.generator/" in name || "/app.bsky.graph.list/" in name)

    fun remember(uri: String, displayName: String?) {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (names[uri] == name) return
        names[uri] = name
        prefs.edit().putString(uri, name).apply()
    }

    /** A feed's record key ("whats-hot") until its name is known. */
    private fun fallback(uri: String) = uri.substringAfterLast('/').ifBlank { uri }
}

/** A Bluesky sign-in: who, where their data lives, and the session's tokens. */
data class BlueskyLogin(
    val handle: String,
    val did: String,
    /** The PDS host, e.g. "morel.us-east.host.bsky.network". */
    val pds: String,
    val accessToken: String,
    val refreshToken: String,
)

/** A Bluesky sign-in that needs the code Bluesky emailed. */
class NeedsEmailCode : Exception("Bluesky emailed you a sign-in code. Enter it below.")

/**
 * Bluesky, through the AT Protocol's XRPC API. Signed in, requests go to the account's
 * PDS, which answers app.bsky.* calls by asking Bluesky's AppView. Signed out, reads
 * go straight to the public AppView. Access tokens last a couple of hours; the refresh
 * token renews them, and each renewal replaces both, so they're saved every time.
 */
class BlueskyService(
    context: Context,
    /** The PDS host. Null until signed in. */
    @Volatile var pds: String?,
    @Volatile var accessToken: String?,
    @Volatile var refreshToken: String?,
    private val onTokens: (access: String, refresh: String) -> Unit,
    private val onTokenRejected: () -> Unit,
) : Service {

    override val log = DebugLog()
    override val kind = ServiceKind.Bluesky
    override val naming = BlueskyNaming(context)
    override val links = BlueskyLinks()
    override val feedSorts = emptyList<Sort>()
    override val commentSorts = listOf(Sort("top", "Top"), Sort("newest", "Newest"), Sort("oldest", "Oldest"))
    override val fixedFeeds = emptyList<FixedFeed>()
    override val signIn = SignIn.Form
    override val downvotes = false
    override val discoverSorts = emptyList<Sort>()
    override val peekSort: Sort? = null
    override val peekLabel = "Latest posts"

    override var authNote: String? = null
        private set

    private val refreshing = Mutex()

    /** What votes and saves need that a name alone doesn't carry, learned as posts are read. */
    private val cids = HashMap<String, String>()
    private val likeUris = HashMap<String, String>()

    private val parse = BlueskyParse(naming) { uri, cid, like ->
        cids[uri] = cid
        if (like != null) likeUris[uri] = like else likeUris.remove(uri)
    }

    // --- Account ---

    override suspend fun whoAmI(): String? {
        authNote = null
        if (accessToken == null) return null
        val me = try {
            get("com.atproto.server.getSession")
        } catch (e: TokenRejected) {
            return null
        }
        if (!me.optBoolean("active", true)) {
            authNote = when (me.optString("status")) {
                "takendown" -> "Bluesky has taken this account down."
                "suspended" -> "Bluesky has suspended this account."
                "deactivated" -> "This account is deactivated. Reactivate it on bsky.app to use it again."
                else -> "Bluesky says this account isn't active."
            }
        }
        val handle = me.str("handle")
        log.note("Signed in as: ${handle ?: "nobody"}")
        return handle
    }

    /** Saved feeds and lists, as their at:// URIs, A to Z by name. */
    override suspend fun subscriptions(): List<String> {
        if (accessToken == null) return emptyList()
        val uris = savedFeeds(get("app.bsky.actor.getPreferences").optJSONArray("preferences") ?: JSONArray())
        learnNames(uris)
        return uris.distinct().sortedBy { naming.label(it).lowercase() }
    }

    /** The URIs of saved feeds and lists in the preferences, newer format first. */
    private fun savedFeeds(prefs: JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until prefs.length()) {
            val p = prefs.optJSONObject(i) ?: continue
            when (p.optString("\$type")) {
                SAVED_V2 -> p.optJSONArray("items")?.let { items ->
                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j) ?: continue
                        if (item.optString("type") in setOf("feed", "list")) item.str("value")?.let(out::add)
                    }
                }
            }
        }
        if (out.isNotEmpty()) return out
        // Accounts that never moved to the newer format.
        for (i in 0 until prefs.length()) {
            val p = prefs.optJSONObject(i) ?: continue
            if (p.optString("\$type") != SAVED_V1) continue
            p.optJSONArray("saved")?.let { s -> for (j in 0 until s.length()) out += s.optString(j) }
        }
        return out.filter { it.startsWith("at://") }
    }

    /** Looks up display names for feeds and lists the bar doesn't have names for yet. */
    private suspend fun learnNames(uris: List<String>) {
        val feeds = uris.filter { GENERATOR in it }
        feeds.chunked(25).forEach { chunk ->
            runCatching {
                val list = get("app.bsky.feed.getFeedGenerators", chunk.map { "feeds" to it }).optJSONArray("feeds") ?: JSONArray()
                for (i in 0 until list.length()) parse.generator(list.getJSONObject(i))
            }
        }
        uris.filter { LIST in it }.forEach { uri ->
            runCatching {
                val l = get("app.bsky.graph.getList", listOf("list" to uri, "limit" to "1")).getJSONObject("list")
                naming.remember(uri, l.str("name"))
            }
        }
    }

    // --- Reading ---

    /** Home is the Following timeline; signed out, the Discover feed. */
    override fun isMixed(community: String?): Boolean = community == null

    override suspend fun feed(community: String?, sort: Sort?, cursor: String?): FeedPage {
        val params = mutableListOf("limit" to PAGE_SIZE.toString())
        cursor?.let { params += "cursor" to it }
        val root = when {
            community == null && accessToken != null -> get("app.bsky.feed.getTimeline", params)
            community == null -> get("app.bsky.feed.getFeed", params + ("feed" to DISCOVER_FEED))
            LIST in community -> get("app.bsky.feed.getListFeed", params + ("list" to canonical(community)))
            else -> get("app.bsky.feed.getFeed", params + ("feed" to canonical(community)))
        }
        val list = root.optJSONArray("feed") ?: JSONArray()
        val posts = (0 until list.length()).mapNotNull { parse.feedItem(list.getJSONObject(it)) }
        return FeedPage(posts, root.str("cursor")?.takeIf { list.length() > 0 })
    }

    override suspend fun thread(ref: String, sort: Sort?): ThreadData {
        val root = get("app.bsky.feed.getPostThread", listOf("uri" to canonical(ref), "depth" to DEPTH, "parentHeight" to "0"))
        val node = root.getJSONObject("thread")
        if (node.optString("\$type") != THREAD_POST) throw ServiceException(parse.missing(node))
        val post = parse.post(node.getJSONObject("post"))
        val items = parse.replies(node, post.name, 0, post.author, sort?.key ?: commentSorts.first().key)
        return ThreadData(post, items, isSingleThread = false)
    }

    /** Replies past the depth limit: the reply's own thread, placed under it. */
    override suspend fun moreChildren(post: Post, more: More, sort: Sort?): List<ThreadItem> {
        val uri = more.children.first()
        val node = get("app.bsky.feed.getPostThread", listOf("uri" to uri, "depth" to DEPTH, "parentHeight" to "0"))
            .getJSONObject("thread")
        return parse.replies(node, uri, more.depth, post.author, sort?.key ?: commentSorts.first().key)
    }

    override fun continueRef(post: Post?, threadRef: String, more: More): String = more.parentName

    // --- Likes and bookmarks ---

    override suspend fun vote(name: String, dir: Int) {
        requireSignedIn()
        val repo = did()
        if (dir == 1) {
            if (likeUris.containsKey(name)) return
            val record = JSONObject()
                .put("\$type", "app.bsky.feed.like")
                .put("subject", JSONObject().put("uri", name).put("cid", cidOf(name)))
                .put("createdAt", now())
            val made = post("com.atproto.repo.createRecord", JSONObject().put("repo", repo).put("collection", "app.bsky.feed.like").put("record", record))
            made.str("uri")?.let { likeUris[name] = it }
        } else {
            val like = likeUris[name] ?: run { cidOf(name, refresh = true); likeUris[name] } ?: return
            post(
                "com.atproto.repo.deleteRecord",
                JSONObject().put("repo", repo).put("collection", "app.bsky.feed.like").put("rkey", like.substringAfterLast('/')),
            )
            likeUris.remove(name)
        }
    }

    override suspend fun setSaved(name: String, saved: Boolean) {
        requireSignedIn()
        if (saved) {
            post("app.bsky.bookmark.createBookmark", JSONObject().put("uri", name).put("cid", cidOf(name)))
        } else {
            post("app.bsky.bookmark.deleteBookmark", JSONObject().put("uri", name))
        }
    }

    /** A post's cid, which likes and bookmarks need; fetches the post (and its like) if unknown. */
    private suspend fun cidOf(uri: String, refresh: Boolean = false): String {
        if (!refresh) cids[uri]?.let { return it }
        val posts = get("app.bsky.feed.getPosts", listOf("uris" to uri)).optJSONArray("posts") ?: JSONArray()
        if (posts.length() == 0) throw ServiceException("That post is gone.")
        parse.post(posts.getJSONObject(0)) // records its cid and like
        return cids[uri] ?: throw ServiceException("That post is gone.")
    }

    // --- Discover: feeds ---

    override suspend fun discover(sort: Sort?, cursor: String?): CommunityPage = popularFeeds(null, cursor)

    override suspend fun searchCommunities(query: String, cursor: String?): CommunityPage = popularFeeds(query, cursor)

    private suspend fun popularFeeds(query: String?, cursor: String?): CommunityPage {
        val params = mutableListOf("limit" to DISCOVER_PAGE.toString())
        query?.let { params += "query" to it }
        cursor?.let { params += "cursor" to it }
        val root = get("app.bsky.unspecced.getPopularFeedGenerators", params)
        val list = root.optJSONArray("feeds") ?: JSONArray()
        val feeds = (0 until list.length()).map { parse.generator(list.getJSONObject(it)) }
        return CommunityPage(feeds, root.str("cursor")?.takeIf { list.length() > 0 })
    }

    /** Saves a feed to (or removes it from) the account's saved feeds, pinned so it shows in Bluesky's tabs too. */
    override suspend fun setSubscribed(community: String, subscribed: Boolean) {
        requireSignedIn()
        val uri = canonical(community)
        val prefs = get("app.bsky.actor.getPreferences").optJSONArray("preferences") ?: JSONArray()
        var v2: JSONObject? = null
        for (i in 0 until prefs.length()) {
            val p = prefs.optJSONObject(i) ?: continue
            if (p.optString("\$type") == SAVED_V2) v2 = p
        }
        val pref = v2 ?: JSONObject().put("\$type", SAVED_V2).put(
            "items",
            JSONArray().put(JSONObject().put("type", "timeline").put("value", "following").put("pinned", true).put("id", tid())),
        ).also { prefs.put(it) }
        val items = pref.optJSONArray("items") ?: JSONArray().also { pref.put("items", it) }
        val kept = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("value") != uri && item.optString("value") != community) kept.put(item)
        }
        if (subscribed) {
            kept.put(JSONObject().put("type", if (LIST in uri) "list" else "feed").put("value", uri).put("pinned", true).put("id", tid()))
        }
        pref.put("items", kept)
        post("app.bsky.actor.putPreferences", JSONObject().put("preferences", prefs))
    }

    // --- Plumbing ---

    /** at:// URIs with a handle where the DID goes (from bsky.app links) get the DID. */
    private suspend fun canonical(uri: String): String {
        if (!uri.startsWith("at://")) return uri
        val authority = uri.removePrefix("at://").substringBefore('/')
        if (authority.startsWith("did:")) return uri
        val did = get("com.atproto.identity.resolveHandle", listOf("handle" to authority)).str("did") ?: return uri
        return "at://$did" + uri.removePrefix("at://$authority")
    }

    /** The signed-in account's DID: the access token's subject. */
    private suspend fun did(): String {
        accessToken?.let(::jwtSubject)?.let { return it }
        return get("com.atproto.server.getSession").str("did") ?: throw ServiceException("Sign in first")
    }

    /** Ends the session on the PDS, so its tokens stop working. */
    suspend fun logOut() {
        val refresh = refreshToken ?: return
        val host = pds ?: return
        xrpc(log, "https://$host", "POST", "com.atproto.server.deleteSession", emptyList(), null, refresh)
    }

    override fun forget() {
        cids.clear()
        likeUris.clear()
    }

    private fun requireSignedIn() {
        if (accessToken == null) throw ServiceException("Sign in first")
    }

    private class TokenRejected : ServiceException("Bluesky ended this sign-in. Sign in again.")

    private suspend fun get(nsid: String, params: List<Pair<String, String>> = emptyList()) = call("GET", nsid, params, null)

    private suspend fun post(nsid: String, body: JSONObject) = call("POST", nsid, emptyList(), body)

    /** One XRPC call, renewing the access token once if it has run out. */
    private suspend fun call(method: String, nsid: String, params: List<Pair<String, String>>, body: JSONObject?): JSONObject {
        val token = accessToken
        val base = pds?.takeIf { token != null }?.let { "https://$it" } ?: PUBLIC_APPVIEW
        try {
            return xrpc(log, base, method, nsid, params, body, token)
        } catch (e: BlueskyError) {
            if (token == null || !e.isExpired) throw e
        }
        renew(token)
        return xrpc(log, "https://$pds", method, nsid, params, body, accessToken)
    }

    /** Swaps the refresh token for new tokens, unless another call already did. */
    private suspend fun renew(expired: String) = refreshing.withLock {
        if (accessToken != expired) return@withLock
        val refresh = refreshToken ?: rejectToken()
        val fresh = try {
            xrpc(log, "https://$pds", "POST", "com.atproto.server.refreshSession", emptyList(), null, refresh)
        } catch (e: BlueskyError) {
            if (e.status == 400 || e.status == 401) rejectToken() else throw e
        }
        val access = fresh.str("accessJwt") ?: rejectToken()
        val newRefresh = fresh.str("refreshJwt") ?: refresh
        accessToken = access
        refreshToken = newRefresh
        log.note("Renewed the sign-in")
        onTokens(access, newRefresh)
    }

    private fun rejectToken(): Nothing {
        log.note("Bluesky no longer accepts the sign-in")
        authNote = "Bluesky ended this sign-in. Sign-ins end when an app password is revoked, or after a long time unused."
        accessToken = null
        refreshToken = null
        onTokenRejected()
        throw TokenRejected()
    }

    companion object {
        private const val PUBLIC_APPVIEW = "https://public.api.bsky.app"
        private const val ENTRYWAY = "https://bsky.social"
        /** Bluesky's own "Discover" feed, for Home while signed out. */
        const val DISCOVER_FEED = "at://did:plc:z72i7hdynmk6r22z27h6tvur/app.bsky.feed.generator/whats-hot"
        const val GENERATOR = "/app.bsky.feed.generator/"
        const val LIST = "/app.bsky.graph.list/"
        private const val THREAD_POST = "app.bsky.feed.defs#threadViewPost"
        private const val SAVED_V2 = "app.bsky.actor.defs#savedFeedsPrefV2"
        private const val SAVED_V1 = "app.bsky.actor.defs#savedFeedsPref"
        private const val PAGE_SIZE = 30
        private const val DISCOVER_PAGE = 50
        private const val DEPTH = "8"
        private const val TIMEOUT_MS = 20_000

        /**
         * Signs in with a handle (or email) and an app password. Finds the account's PDS
         * from its handle, so accounts hosted anywhere work; emails go to bsky.social.
         * Throws [NeedsEmailCode] when Bluesky wants the code it emailed.
         */
        suspend fun login(identifier: String, password: String, emailCode: String?): BlueskyLogin {
            val log = DebugLog()
            val id = normalizeHandle(identifier)
            val start = if ('@' in id) ENTRYWAY else runCatching { pdsOf(log, resolveDid(log, id)) }.getOrNull() ?: ENTRYWAY
            val body = JSONObject().put("identifier", id).put("password", password)
            if (!emailCode.isNullOrBlank()) body.put("authFactorToken", emailCode.trim())
            val s = try {
                xrpc(log, start, "POST", "com.atproto.server.createSession", emptyList(), body, null)
            } catch (e: BlueskyError) {
                when {
                    e.code == "AuthFactorTokenRequired" -> throw NeedsEmailCode()
                    e.code == "AccountTakedown" -> throw ServiceException("Bluesky has taken this account down.")
                    e.status == 401 && !emailCode.isNullOrBlank() -> throw ServiceException("That code didn't work, or the password is wrong.")
                    e.status == 401 -> throw ServiceException("Wrong handle or app password.")
                    e.status == 429 -> throw ServiceException("Too many tries. Wait a few minutes and try again.")
                    else -> throw e
                }
            }
            val pds = s.optJSONObject("didDoc")?.let(::pdsIn) ?: start
            return BlueskyLogin(
                handle = s.str("handle") ?: id,
                did = s.getString("did"),
                pds = pds.removePrefix("https://").trimEnd('/'),
                accessToken = s.getString("accessJwt"),
                refreshToken = s.getString("refreshJwt"),
            )
        }

        /** "@Alice" to "alice.bsky.social"; full handles and emails as typed (minus spaces and @). */
        fun normalizeHandle(typed: String): String {
            val t = typed.trim().removePrefix("@")
            if ('@' in t) return t // an email
            val h = t.lowercase()
            return if ('.' in h) h else "$h.bsky.social"
        }

        private suspend fun resolveDid(log: DebugLog, handle: String): String =
            xrpc(log, PUBLIC_APPVIEW, "GET", "com.atproto.identity.resolveHandle", listOf("handle" to handle), null, null)
                .getString("did")

        /** The PDS in a DID's document: plc.directory for did:plc, the host itself for did:web. */
        private suspend fun pdsOf(log: DebugLog, did: String): String? {
            val url = when {
                did.startsWith("did:plc:") -> "https://plc.directory/$did"
                did.startsWith("did:web:") -> "https://${did.removePrefix("did:web:")}/.well-known/did.json"
                else -> return null
            }
            val (status, text) = fetch(log, "GET", url, null, null)
            if (status != 200) return null
            return runCatching { pdsIn(JSONObject(text)) }.getOrNull()
        }

        private fun pdsIn(doc: JSONObject): String? {
            val services = doc.optJSONArray("service") ?: return null
            for (i in 0 until services.length()) {
                val s = services.optJSONObject(i) ?: continue
                if (s.optString("id").endsWith("#atproto_pds")) return s.str("serviceEndpoint")?.trimEnd('/')
            }
            return null
        }

        private suspend fun xrpc(
            log: DebugLog,
            base: String,
            method: String,
            nsid: String,
            params: List<Pair<String, String>>,
            body: JSONObject?,
            bearer: String?,
        ): JSONObject {
            log.note("$method ${base.removePrefix("https://")} $nsid")
            val query = params.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
            val (status, text) = fetch(log, method, "$base/xrpc/$nsid" + if (query.isEmpty()) "" else "?$query", body, bearer)
            if (status != 200) log.note("  -> HTTP $status")
            val json = runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrNull()
            if (status == 200 && json != null) return json
            val code = json?.str("error")
            val host = base.removePrefix("https://")
            val message = when {
                status == 0 -> "Couldn't reach Bluesky. Check your connection."
                status == 429 || code == "RateLimitExceeded" -> "Bluesky is rate limiting. Wait a minute and try again."
                code == "NotFound" || status == 404 -> "Not found."
                code == "BlockedActor" || code == "BlockedByActor" -> "You can't see this because of a block."
                json?.str("message") != null -> json.str("message")!!
                code != null -> "Bluesky said: $code"
                else -> "$host returned an error ($status)."
            }
            throw BlueskyError(status, code, message)
        }

        private suspend fun fetch(log: DebugLog, method: String, url: String, body: JSONObject?, bearer: String?): Pair<Int, String> {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = method
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.setRequestProperty("Accept", "application/json")
                    conn.setRequestProperty("User-Agent", "Lurk (Android)")
                    bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                    if (body != null) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    } else if (method == "POST") {
                        conn.doOutput = true
                        conn.setFixedLengthStreamingMode(0)
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                } catch (e: IOException) {
                    0 to (e.message ?: "")
                }
            }
            log.lastResult = result.second.take(4_000)
            return result
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun now() = java.time.Instant.now().toString()

        /** A JWT's "sub" claim, without checking the signature (the server does that). */
        private fun jwtSubject(jwt: String): String? = runCatching {
            val payload = jwt.split('.')[1]
            val json = String(android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
            JSONObject(json).str("sub")?.takeIf { it.startsWith("did:") }
        }.getOrNull()

        private const val TID_CHARS = "234567abcdefghijklmnopqrstuvwxyz"

        /** A timestamp id, the kind Bluesky gives saved-feed entries. */
        private fun tid(): String {
            var v = ((System.currentTimeMillis() * 1000) shl 10) or Random.nextLong(1024)
            val out = CharArray(13)
            for (i in 12 downTo 0) {
                out[i] = TID_CHARS[(v and 31).toInt()]
                v = v ushr 5
            }
            return String(out)
        }
    }
}

/** An error Bluesky reported, e.g. {"error":"ExpiredToken"}. */
class BlueskyError(val status: Int, val code: String?, message: String) : ServiceException(message) {
    val isExpired: Boolean get() = code == "ExpiredToken" || (status == 401 && code == "InvalidToken")
}
