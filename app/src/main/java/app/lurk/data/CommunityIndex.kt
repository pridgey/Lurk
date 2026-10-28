package app.lurk.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One community from [CommunityIndex], with counts from its home instance. */
class IndexedCommunity(
    val name: String,
    val host: String,
    val title: String?,
    /** Plain text, cut short. */
    val description: String,
    val icon: String?,
    val banner: String?,
    val nsfw: Boolean,
    val subscribers: Int,
    val activeWeek: Int,
    val activeMonth: Int,
    val posts: Int,
    /** Epoch millis. */
    val published: Long,
    /** [Topic.key]s. */
    val topics: Set<String>,
)

/**
 * Every Lemmy community Lemmy Explorer (lemmyverse.net) knows, from its nightly crawl:
 * about 26,000, with counts from each community's home instance and [Topics] worked out
 * here. Downloaded in the background (about 5 MB, in 500-community pages), kept in a file,
 * and fetched again when it's a few days old. Shared by every Lemmy account.
 */
class CommunityIndex private constructor(private val file: File) {

    @Volatile private var communities: List<IndexedCommunity>? = null
    @Volatile private var fetchedAt = 0L
    @Volatile private var lastTry = 0L
    private var fileRead = false
    private var refreshing: Deferred<List<IndexedCommunity>?>? = null
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Why the last download failed, when it did. */
    @Volatile var error: String? = null
        private set

    /** What's at hand now, from memory or the saved file; starts a download if there's none or it's old. */
    suspend fun cached(): List<IndexedCommunity>? {
        return lock.withLock {
            if (!fileRead) {
                fileRead = true
                withContext(Dispatchers.IO) { loadFile() }
            }
            if (needsRefresh()) startRefresh()
            communities
        }
    }

    /** What's at hand, or waits for a download when there's nothing yet. Null if that fails. */
    suspend fun await(): List<IndexedCommunity>? {
        cached()?.let { return it }
        val job = lock.withLock { communities?.let { return it }; startRefresh() }
        return job.await()
    }

    private fun needsRefresh(): Boolean {
        val now = System.currentTimeMillis()
        if (refreshing?.isActive == true || now - lastTry < RETRY_MS) return false
        return communities == null || now - fetchedAt > MAX_AGE_MS
    }

    /** Called with [lock] held. */
    private fun startRefresh(): Deferred<List<IndexedCommunity>?> {
        refreshing?.takeIf { it.isActive }?.let { return it }
        lastTry = System.currentTimeMillis()
        return scope.async {
            try {
                val fresh = download()
                save(fresh)
                communities = fresh
                fetchedAt = System.currentTimeMillis()
                error = null
                fresh
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load the community list"
                communities
            }
        }.also { refreshing = it }
    }

    private suspend fun download(): List<IndexedCommunity> = withContext(Dispatchers.IO) {
        val count = JSONObject(get("$BASE/community.json")).optInt("count")
        if (count <= 0 || count > MAX_PAGES) throw IOException("lemmyverse.net sent an unexpected page count ($count)")
        val gate = Semaphore(PARALLEL)
        val pages = (0 until count).map { i ->
            async { gate.withPermit { parsePage(JSONArray(get("$BASE/community/$i.json"))) } }
        }.awaitAll()
        pages.flatten().distinctBy { it.name.lowercase() + "@" + it.host }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Lurk (Android)")
        try {
            if (conn.responseCode != 200) throw IOException("lemmyverse.net returned an error (${conn.responseCode})")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw IOException(e.message?.takeIf { "lemmyverse" in it } ?: "Couldn't reach lemmyverse.net", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun loadFile() {
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val list = decode(root.getJSONArray("communities"))
            communities = list
            // Tagged by older topic lists: still usable, but fetch again to tag afresh.
            fetchedAt = if (root.optInt("topics") == Topics.VERSION) root.optLong("fetched") else 0L
        }.onFailure { file.delete() }
    }

    private fun save(list: List<IndexedCommunity>) {
        val root = JSONObject()
            .put("fetched", System.currentTimeMillis())
            .put("topics", Topics.VERSION)
            .put("communities", encode(list))
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) temp.delete()
    }

    companion object {
        private const val BASE = "https://lemmyverse.net/data"
        private const val PARALLEL = 4
        private const val MAX_PAGES = 400
        private const val TIMEOUT_MS = 30_000
        private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
        /** After a failed download, wait this long before trying again. */
        private const val RETRY_MS = 10L * 60 * 1000
        private const val DESCRIPTION_CHARS = 240

        @Volatile private var shared: CommunityIndex? = null

        fun get(context: Context): CommunityIndex = shared ?: synchronized(this) {
            shared ?: CommunityIndex(File(context.filesDir, "community-index.json")).also { shared = it }
        }

        /** One page of Lemmy Explorer's community list. Leaves out what it marks as suspicious. */
        fun parsePage(page: JSONArray): List<IndexedCommunity> = (0 until page.length()).mapNotNull { i ->
            val c = page.optJSONObject(i) ?: return@mapNotNull null
            if (c.optBoolean("isSuspicious")) return@mapNotNull null
            val name = c.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val host = c.optString("baseurl").lowercase().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val counts = c.optJSONObject("counts") ?: JSONObject()
            val title = c.text("title")?.takeIf { !it.equals(name, ignoreCase = true) }
            // Long ones are cut first: the start says what a community is about.
            val description = c.text("desc")?.take(2_000)?.let(Markdown::plain).orEmpty()
            IndexedCommunity(
                name = name,
                host = host,
                title = title,
                description = description.take(DESCRIPTION_CHARS),
                icon = c.text("icon"),
                banner = c.text("banner"),
                nsfw = c.optBoolean("nsfw"),
                subscribers = counts.optInt("subscribers"),
                activeWeek = counts.optInt("users_active_week"),
                activeMonth = counts.optInt("users_active_month"),
                posts = counts.optInt("posts"),
                published = c.optLong("published"),
                topics = Topics.of(name, title, description),
            )
        }

        private fun encode(list: List<IndexedCommunity>) = JSONArray().apply {
            list.forEach { c ->
                put(
                    JSONObject()
                        .put("n", c.name).put("h", c.host).put("t", c.title).put("d", c.description)
                        .put("i", c.icon).put("b", c.banner).put("x", c.nsfw)
                        .put("s", c.subscribers).put("w", c.activeWeek).put("m", c.activeMonth)
                        .put("p", c.posts).put("at", c.published).put("k", JSONArray(c.topics.toList())),
                )
            }
        }

        private fun decode(array: JSONArray): List<IndexedCommunity> = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val keys = o.optJSONArray("k") ?: JSONArray()
            IndexedCommunity(
                name = o.getString("n"),
                host = o.getString("h"),
                title = o.text("t"),
                description = o.optString("d"),
                icon = o.text("i"),
                banner = o.text("b"),
                nsfw = o.optBoolean("x"),
                subscribers = o.optInt("s"),
                activeWeek = o.optInt("w"),
                activeMonth = o.optInt("m"),
                posts = o.optInt("p"),
                published = o.optLong("at"),
                topics = (0 until keys.length()).mapTo(LinkedHashSet()) { keys.getString(it) },
            )
        }

        private fun JSONObject.text(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
