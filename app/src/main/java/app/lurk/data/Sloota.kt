package app.lurk.data

import android.content.Context
import android.content.Intent

/**
 * Tells Sloota (our app-limit app, github.com/pridgey/Sloota) when Lurk reaches a natural stopping point,
 * so a session past its daily limit can end there instead of mid-post.
 *
 * Fire-and-forget: if Sloota isn't installed, or Lurk has no limit, nothing happens.
 */
object Sloota {
    private const val ACTION = "dev.pridgey.sloota.STOPPING_POINT"
    /** Release and debug builds of Sloota. */
    private val PACKAGES = listOf("dev.pridgey.sloota", "dev.pridgey.sloota.debug")

    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** [reason] shows up in Sloota's log, e.g. "back", "video_closed", "discover_card". */
    fun stoppingPoint(reason: String) {
        val context = app ?: return
        PACKAGES.forEach { pkg ->
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION)
                        .setPackage(pkg)
                        .putExtra("pkg", context.packageName)
                        .putExtra("reason", reason),
                )
            }
        }
    }
}
