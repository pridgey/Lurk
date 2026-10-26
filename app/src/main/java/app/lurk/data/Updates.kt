package app.lurk.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A build published on the repo's "latest" release. */
data class Release(
    val build: Int,
    val apkUrl: String,
    val size: Long,
    /** Lowercase hex, when GitHub reports one. */
    val sha256: String?,
    /** The "- " lines of the release notes: recent commit subjects. */
    val changes: List<String>,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    /** [progress] is 0..1, or null before the size is known. */
    data class Downloading(val release: Release, val progress: Float?) : UpdateState
    /** Handed to Android's installer; it asks to confirm, then restarts the app. */
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Updates Lurk from the GitHub repo's "latest" release, which CI replaces on every push
 * to main. The APK's name carries its build number (Lurk-<n>.apk), compared with this
 * install's versionCode. Android only accepts the new APK if it's signed with the same
 * key, so a bad download can't replace the app.
 */
class Updater(private val context: Context) {

    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Outlives any one screen, so closing the dialog doesn't stop a download. */
    private val scope = MainScope()

    val installedBuild: Int =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
        }

    /** Debug builds are signed with a different key, so a release can't install over them. */
    private val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** The update to point out, if one is known. */
    val available: Release?
        get() = when (val s = state) {
            is UpdateState.Available -> s.release
            is UpdateState.Downloading -> s.release
            is UpdateState.Installing -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        }

    init {
        active = this
    }

    /** Checks on launch, at most every few hours (GitHub allows 60 unsigned checks an hour). */
    suspend fun checkIfDue() {
        if (isDebugBuild) return
        val last = prefs.getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) {
            // Still offer an update found by the last check.
            val known = prefs.getInt(KEY_KNOWN_BUILD, 0)
            if (known <= installedBuild) return
        }
        check()
    }

    suspend fun check() {
        if (state is UpdateState.Downloading || state is UpdateState.Installing) return
        state = UpdateState.Checking
        state = try {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).putInt(KEY_KNOWN_BUILD, release?.build ?: 0).apply()
            when {
                release == null -> UpdateState.Failed("The latest release has no APK yet.", null)
                release.build > installedBuild -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: "Couldn't check for updates.", null)
        }
    }

    /** Puts a finished "up to date" or failed check back to quiet. */
    fun dismiss() {
        if (state is UpdateState.UpToDate || (state as? UpdateState.Failed)?.release == null) state = UpdateState.Idle
    }

    /**
     * Whether Lurk may install apps. If not, opens the system page to allow it; call
     * [startInstall] again when the person comes back.
     */
    fun canInstall(): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return false
    }

    fun startCheck() {
        scope.launch { check() }
    }

    fun startInstall(release: Release) {
        scope.launch { downloadAndInstall(release) }
    }

    private suspend fun downloadAndInstall(release: Release) {
        if (state is UpdateState.Downloading || state is UpdateState.Installing) return
        state = UpdateState.Downloading(release, null)
        try {
            val apk = withContext(Dispatchers.IO) { download(release) }
            state = UpdateState.Installing(release)
            withContext(Dispatchers.IO) { install(apk) }
        } catch (e: CancellationException) {
            state = UpdateState.Available(release)
            throw e
        } catch (e: Exception) {
            state = UpdateState.Failed(e.message ?: "The update didn't download.", release)
        }
    }

    /** Called by [InstallResultReceiver] when Android's installer finishes or gives up. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = available
        state = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate // the app restarts on the new build
            PackageInstaller.STATUS_FAILURE_ABORTED -> release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed(
                    "Android refused the update. It's probably signed with a different key than this install " +
                        "(e.g. this is a local build). Uninstall Lurk and install the release APK once to fix it.",
                    release,
                )
            else -> UpdateState.Failed("Install failed: ${message ?: "unknown error"}", release)
        }
    }

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/tags/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "Lurk/$installedBuild")
            when (val status = conn.responseCode) {
                200 -> Unit
                404 -> throw IOException("No release found. The repo may be private, or no build has been published yet.")
                403, 429 -> throw IOException("GitHub is rate limiting update checks. Try again in an hour.")
                else -> throw IOException("GitHub returned an error ($status).")
            }
            return parseRelease(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // old downloads
        val file = File(dir, "Lurk-${release.build}.apk")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true // GitHub redirects to its file host
            conn.setRequestProperty("User-Agent", "Lurk/$installedBuild")
            if (conn.responseCode != 200) throw IOException("The download failed (${conn.responseCode}).")
            val total = release.size.takeIf { it > 0 } ?: conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastShown = 0f
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val p = read.toFloat() / total
                            if (p - lastShown >= 0.01f) {
                                lastShown = p
                                state = UpdateState.Downloading(release, p.coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
            if (release.size > 0 && read != release.size) throw IOException("The download was cut short. Try again.")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && hex != release.sha256) {
                file.delete()
                throw IOException("The download didn't match the release's checksum. Try again.")
            }
            return file
        } finally {
            conn.disconnect()
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Once Lurk installed itself, later updates may skip the confirmation (Android 12+).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("lurk.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName), flags,
            )
            session.commit(result.intentSender)
        }
    }

    companion object {
        const val REPO = "pridgey/Lurk"
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val KEY_KNOWN_BUILD = "knownBuild"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

        /** The running app's updater, for [InstallResultReceiver]. */
        @Volatile internal var active: Updater? = null

        private val apkName = Regex("""Lurk-(\d+)\.apk""")

        /** The release's APK and build number; null if it has none. */
        fun parseRelease(json: JSONObject): Release? {
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val build = apkName.matchEntire(a.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val url = a.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: continue
                return Release(
                    build = build,
                    apkUrl = url,
                    size = a.optLong("size"),
                    sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
                    changes = json.optString("body").lines()
                        .map { it.trim() }
                        .filter { it.startsWith("- ") }
                        .map { it.removePrefix("- ").trim() }
                        .filter { it.isNotEmpty() },
                )
            }
            return null
        }
    }
}

/** Gets Android's installer's answer: ask the person to confirm, or report how it went. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Updater.active?.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
