package app.lurk.data

import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Engine debug's "Test sign-in": whether a Reddit login cookie still gets Lurk in once RSS
 * and logged-out JSON are gone. It trades the cookie for a token the way Reddit's Android
 * app does (as Redlib's forks do), then reads a few pages from the API with it. Logs status
 * codes, counts and rate-limit headers; never the cookie or the token.
 *
 * This poses as Reddit's own app, which Reddit's terms don't allow: test with a spare account.
 */
object TokenProbe {

    /** Reddit's Android app's public client ID. */
    private const val CLIENT_ID = "ohXpoqrZYub1kg"
    private const val APP_VERSION = "Version 2026.42.0/Build 2642021"
    private const val GAP_MS = 2_000L

    /** The cookie value from a pasted "reddit_session=…" (or the bare value), or null. */
    fun parseCookie(pasted: String): String? {
        val text = pasted.trim().trim(';')
        val value = text.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("reddit_session=") }?.substringAfter('=')
            ?: text.takeIf { '=' !in it && ' ' !in it }
        return value?.takeIf { it.isNotBlank() }
    }

    suspend fun run(log: DebugLog, build: Int, cookie: String, source: String) {
        val device = UUID.randomUUID().toString()
        val app = mapOf(
            "User-Agent" to "Reddit/$APP_VERSION/Android 15",
            "X-Reddit-Device-Id" to device,
            "client-vendor-id" to device,
            "x-reddit-retry" to "algo=no-retries",
            "x-reddit-compression" to "1",
        )
        val lurk = mapOf("User-Agent" to "Lurk/$build (Android)")
        log.note("Sign-in test: login cookie from $source")

        // 1. Logged out, for comparison: Reddit has said this is blocked.
        log.note("1 (logged out, www JSON): " + get("https://www.reddit.com/r/pics/hot.json?limit=5&raw_json=1", lurk).line)
        delay(GAP_MS)

        // 2. Cookie for token, as the Android app does it.
        val mint = request(
            "POST", "https://www.reddit.com/auth/v2/oauth/access-token/session",
            app + mapOf(
                "Cookie" to "reddit_session=$cookie",
                "Authorization" to "Basic " + Base64.encodeToString("$CLIENT_ID:".toByteArray(), Base64.NO_WRAP),
                "Content-Type" to "application/json; charset=UTF-8",
            ),
            body = JSONObject().put("scopes", JSONArray(listOf("*", "email", "pii"))).toString(),
        )
        val json = mint.json()
        val token = json?.optString("access_token")?.takeIf { it.isNotBlank() }
        log.note(
            "2 (token from cookie): ${mint.line}" +
                (if (token != null) ", got a token, expires in ${json?.optLong("expires_in")} s" else ", no token") +
                (if (mint.newSession) ", sent a new login cookie" else "") +
                (json?.optString("error")?.takeIf { it.isNotBlank() }?.let { ", error=$it" } ?: ""),
        )
        if (token == null) {
            log.note("Sign-in test done: no token, so the API steps were skipped")
            return
        }
        val api = app + mapOf("Authorization" to "Bearer $token")
        delay(GAP_MS)

        // 3. Which account the token is for.
        val me = get("https://oauth.reddit.com/api/v1/me?raw_json=1", api)
        log.note("3 (who am I): ${me.line}" + (me.json()?.optString("name")?.takeIf { it.isNotBlank() }?.let { ", u/$it" } ?: ""))
        delay(GAP_MS)

        // 4. The account's front page.
        val best = get("https://oauth.reddit.com/best?limit=10&raw_json=1", api)
        log.note("4 (front page): ${best.line}${best.postCount()}")
        delay(GAP_MS)

        // 5. A subreddit, and a thread from it.
        val sub = get("https://oauth.reddit.com/r/pics/hot?limit=5&raw_json=1", api)
        log.note("5 (r/pics): ${sub.line}${sub.postCount()}")
        val first = sub.json()?.optJSONObject("data")?.optJSONArray("children")?.let { posts ->
            (0 until posts.length()).map { posts.getJSONObject(it).optJSONObject("data") }
                .firstOrNull { it != null && !it.optBoolean("stickied") }
        }
        val id = first?.optString("id")?.takeIf { it.isNotBlank() }
        if (id != null) {
            delay(GAP_MS)
            // The path Reddit blocked for logged-out requests in October, then the short form.
            val long = get("https://oauth.reddit.com/r/pics/comments/$id?limit=20&raw_json=1", api)
            log.note("6 (thread, /r/pics/comments/…): ${long.line}${long.commentCount()}")
            delay(GAP_MS)
            val short = get("https://oauth.reddit.com/comments/$id?limit=20&raw_json=1", api)
            log.note("7 (thread, /comments/…): ${short.line}${short.commentCount()}")
        } else {
            log.note("6-7 skipped: no post to open")
        }
        delay(GAP_MS)

        // 8. Subscriptions, for the community bar.
        val subs = get("https://oauth.reddit.com/subreddits/mine/subscriber?limit=100&raw_json=1", api)
        log.note("8 (subscriptions): ${subs.line}${subs.postCount("subreddits")}")
        delay(GAP_MS)

        // 9. The cookie alone on www, as Lurk's old engine asked (without the WebView).
        val web = get("https://www.reddit.com/r/pics/hot.json?limit=5&raw_json=1", lurk + mapOf("Cookie" to "reddit_session=$cookie"))
        log.note("9 (cookie only, www JSON): ${web.line}${web.postCount()}")
        log.note("Sign-in test done")
    }

    private class Result(val line: String, val status: Int?, val body: String?, val newSession: Boolean = false) {
        fun json(): JSONObject? = body?.let { runCatching { JSONObject(it) }.getOrNull() }

        fun postCount(noun: String = "posts"): String =
            json()?.optJSONObject("data")?.optJSONArray("children")?.let { ", ${it.length()} $noun" }.orEmpty()

        /** A thread comes back as [post listing, comment listing]. */
        fun commentCount(): String =
            body?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?.optJSONObject(1)?.optJSONObject("data")?.optJSONArray("children")
                ?.let { ", ${it.length()} top-level comments" }.orEmpty()
    }

    private suspend fun get(url: String, headers: Map<String, String>) = request("GET", url, headers, null)

    private suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): Result =
        withContext(Dispatchers.IO) {
            val shown = url.substringBefore('?').removePrefix("https://")
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = false
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = conn.responseCode
                val text = (if (status < 400) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                val blocked = text?.contains("blocked by network security", ignoreCase = true) == true
                val newSession = conn.headerFields.entries
                    .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                    .any { e -> e.value.any { it.startsWith("reddit_session=") } }
                val limits = listOf("x-ratelimit-used", "x-ratelimit-remaining", "x-ratelimit-reset", "location")
                    .mapNotNull { h -> conn.getHeaderField(h)?.let { "$h=${it.substringBefore('?')}" } }
                val line = buildString {
                    append(method).append(' ').append(shown).append(" -> HTTP ").append(status)
                    if (blocked) append(" (network security block page)")
                    if (limits.isNotEmpty()) append(" | ").append(limits.joinToString(" "))
                }
                Result(line, status, text, newSession)
            } catch (e: Exception) {
                Result("$method $shown -> failed: ${e.message}", null, null)
            } finally {
                conn.disconnect()
            }
        }
}
