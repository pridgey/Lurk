package app.lurk.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/** Android wakes this now and then; [LocalFeedSync] decides whether anything is due. */
class LocalFeedWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            LocalFeedSync.run(applicationContext)
        } catch (e: CancellationException) {
            CollectionLog.add(applicationContext, "Android stopped this run before it finished")
            throw e
        } catch (e: Exception) {
            CollectionLog.add(applicationContext, "Run failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return Result.success()
    }
}

/**
 * Fills the collected account's store from public subreddit feeds, over mobile data, a
 * few times a day. Requests carry no Reddit credentials and say they're Lurk. A 403 or
 * 429 stops the run and backs off; it's never retried around.
 */
object LocalFeedSync {

    private const val WORK = "collect-reddit"
    private const val KEY_NEXT = "nextAt"
    private const val KEY_TURN = "turn"
    private const val KEY_FAILS = "fails"
    private const val KEY_LAST = "lastCollected"
    private const val KEY_PROBLEM = "problem"
    private const val KEY_ON_WIFI = "cellularOnWifi"

    private const val WAKE_MINUTES = 30L
    private const val MIN_GAP_MS = 60 * 60_000L
    private const val MAX_GAP_MS = 150 * 60_000L
    private const val BASE_BACKOFF_MS = 2 * 60 * 60_000L
    private const val MAX_BACKOFF_MS = 24 * 60 * 60_000L
    private const val MAX_BATCHES_PER_RUN = 3
    private const val MIN_REQUEST_GAP_MS = 65_000L
    private const val MAX_REQUEST_GAP_MS = 95_000L
    private const val NETWORK_WAIT_MS = 30_000L
    private const val MAX_NAMES = 10
    private const val MAX_WEIGHT = 60

    private fun prefs(context: Context) = context.getSharedPreferences("collected_feed", Context.MODE_PRIVATE)

    /** Starts the background work when there's a collected account, and stops it when there isn't. */
    fun update(context: Context) {
        runCatching {
            val work = WorkManager.getInstance(context)
            if (collectedAccount(context) == null) {
                work.cancelUniqueWork(WORK)
                prefs(context).edit().clear().apply()
                CollectionLog.clear(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<LocalFeedWorker>(WAKE_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    /** The list changed: the next wake-up collects instead of waiting out the gap. */
    fun collectSoon(context: Context) {
        val p = prefs(context)
        // A backoff from Reddit still stands.
        if (p.getInt(KEY_FAILS, 0) == 0) p.edit().remove(KEY_NEXT).apply()
    }

    /** Whether collecting may use mobile data while the phone is on wifi. */
    fun cellularOnWifi(context: Context) = prefs(context).getBoolean(KEY_ON_WIFI, false)

    fun setCellularOnWifi(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON_WIFI, on).apply()
    }

    fun lastCollected(context: Context): Long? = prefs(context).getLong(KEY_LAST, 0).takeIf { it > 0 }

    /** What to say over the feed: a problem, or why it's still empty. */
    fun notice(context: Context, empty: Boolean): String? =
        prefs(context).getString(KEY_PROBLEM, null)
            ?: if (empty) "Nothing collected here yet. Lurk checks a few times a day while you're on mobile data." else null

    suspend fun run(context: Context) {
        val account = collectedAccount(context) ?: return
        if (account.second.isEmpty()) {
            CollectionLog.add(context, "Woke up: no subreddits to collect")
            return
        }
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val nextAt = p.getLong(KEY_NEXT, 0)
        if (now < nextAt) {
            CollectionLog.add(context, "Woke up: not due until ${clock(nextAt)}")
            return
        }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        if (!cellularOnWifi(context) && !isOnCellular(cm)) {
            CollectionLog.add(context, "Woke up: skipped, not on mobile data")
            return
        }
        onCellular(context, cm) { network -> collect(context, account.first, account.second, network) }
    }

    private fun clock(at: Long): String = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(at))

    private fun isOnCellular(cm: ConnectivityManager): Boolean {
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Runs [block] on the phone's mobile network, even with wifi up. Skips it when there's none. */
    private suspend fun onCellular(context: Context, cm: ConnectivityManager, block: suspend (Network) -> Unit) {
        val found = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.complete(network)
            }

            override fun onUnavailable() {
                found.complete(null)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.requestNetwork(request, callback, NETWORK_WAIT_MS.toInt())
        try {
            val network = withTimeoutOrNull(NETWORK_WAIT_MS + 1_000) { found.await() }
            if (network != null) block(network)
            else CollectionLog.add(context, "Woke up: skipped, Android couldn't provide a mobile network")
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    private suspend fun collect(context: Context, accountId: String, subs: List<String>, network: Network) {
        val p = prefs(context)
        val store = CollectedPosts.get(context, accountId)
        val all = batches(subs, withContext(Dispatchers.IO) { store.recentCounts() })
        var turn = p.getInt(KEY_TURN, 0)
        val runs = minOf(all.size, MAX_BATCHES_PER_RUN)
        var added = 0
        CollectionLog.add(context, "Collecting over mobile data: $runs of ${all.size} batches")
        for (i in 0 until runs) {
            if (i > 0) delay(Random.nextLong(MIN_REQUEST_GAP_MS, MAX_REQUEST_GAP_MS))
            val batch = all[turn.mod(all.size)]
            val label = batch.joinToString(", ") { "r/$it" }
            val result = withContext(Dispatchers.IO) { fetch(context, network, batch) }
            when (result.status) {
                200 -> {
                    val posts = runCatching { RedditRss.parse(result.body) }.getOrNull()
                    if (posts == null) {
                        CollectionLog.add(context, "$label: HTTP 200, but the feed couldn't be read")
                        finish(context, problem = "Reddit sent a feed Lurk couldn't read.")
                        return
                    }
                    val fresh = withContext(Dispatchers.IO) { store.add(posts) }
                    added += fresh
                    CollectionLog.add(context, "$label: HTTP 200, ${posts.size} posts, $fresh new")
                    turn = (turn + 1).mod(all.size)
                    p.edit().putInt(KEY_TURN, turn).putLong(KEY_LAST, System.currentTimeMillis()).apply()
                }
                403, 429 -> {
                    val fails = p.getInt(KEY_FAILS, 0) + 1
                    val wait = backoffMs(fails, result.retryAfterMs)
                    p.edit()
                        .putInt(KEY_FAILS, fails)
                        .putLong(KEY_NEXT, System.currentTimeMillis() + wait)
                        .putString(KEY_PROBLEM, "Reddit turned Lurk away (${result.status}). Trying again in about ${wait / 3_600_000} h.")
                        .apply()
                    CollectionLog.add(
                        context,
                        "$label: HTTP ${result.status}, refused. Refusal $fails in a row, waiting until ${clock(System.currentTimeMillis() + wait)}",
                    )
                    return
                }
                else -> {
                    val what = if (result.status == 0) "failed: ${result.error ?: "no connection"}" else "HTTP ${result.status}"
                    val next = finish(context, problem = "Couldn't collect new posts last time (${if (result.status == 0) "no connection" else "error ${result.status}"}).")
                    CollectionLog.add(context, "$label: $what. Next fetch after ${clock(next)}")
                    return
                }
            }
        }
        p.edit().putInt(KEY_FAILS, 0).apply()
        val next = finish(context, problem = null)
        CollectionLog.add(context, "Done: $added new posts. Next fetch after ${clock(next)}")
    }

    /** Sets when the next fetch may go out, and returns it. */
    private fun finish(context: Context, problem: String?): Long {
        val next = System.currentTimeMillis() + Random.nextLong(MIN_GAP_MS, MAX_GAP_MS)
        prefs(context).edit()
            .putLong(KEY_NEXT, next)
            .putString(KEY_PROBLEM, problem)
            .apply()
        return next
    }

    private class Fetched(val status: Int, val body: String, val retryAfterMs: Long?, val error: String? = null)

    private fun fetch(context: Context, network: Network, batch: List<String>): Fetched {
        val url = URL("https://www.reddit.com/r/${Uri.encode(batch.joinToString("+"), "+")}/.rss?limit=100")
        return try {
            val conn = network.openConnection(url) as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Lurk/${build(context)} (personal RSS reader; Android)")
                val status = conn.responseCode
                val body = if (status == 200) conn.inputStream.bufferedReader().use { it.readText() } else ""
                val retry = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
                Fetched(status, body, retry)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Fetched(0, "", null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun build(context: Context): Int =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
        }

    /** How long to stay away after Reddit's [fails]th refusal in a row, honoring its own ask. */
    fun backoffMs(fails: Int, retryAfterMs: Long?): Long {
        val doubled = BASE_BACKOFF_MS shl (fails - 1).coerceIn(0, 8)
        return doubled.coerceAtMost(MAX_BACKOFF_MS).coerceAtLeast(retryAfterMs ?: 0)
    }

    /**
     * [subs] grouped into combined feeds, busiest first. A feed returns 100 posts at most,
     * so busy subreddits share with few others and can't crowd quiet ones out.
     * [recent] is posts per subreddit (lowercase) in the last day.
     */
    fun batches(subs: List<String>, recent: Map<String, Int>): List<List<String>> {
        fun weight(s: String) = (recent[s.lowercase()] ?: 1).coerceAtLeast(1)
        val out = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var total = 0
        subs.sortedWith(compareByDescending<String> { weight(it) }.thenBy { it.lowercase() }).forEach { s ->
            if (current.isNotEmpty() && (current.size >= MAX_NAMES || total + weight(s) > MAX_WEIGHT)) {
                out += current
                current = ArrayList()
                total = 0
            }
            current += s
            total += weight(s)
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * The collected account's id and subreddits, read straight from saved accounts.
     * Background work must not build an [AccountStore]: it saves on creation, and could
     * overwrite changes the open app just made.
     */
    private fun collectedAccount(context: Context): Pair<String, List<String>>? = runCatching {
        val raw = context.getSharedPreferences("accounts", Context.MODE_PRIVATE).getString("accounts", null)
            ?: return null
        val arr = JSONArray(raw)
        (0 until arr.length()).map(arr::getJSONObject)
            .firstOrNull { it.optString("kind") == ServiceKind.RedditLocal.name }
            ?.let { o ->
                val subs = o.optJSONArray("subreddits") ?: JSONArray()
                o.getString("id") to (0 until subs.length()).map(subs::getString)
            }
    }.getOrNull()
}
