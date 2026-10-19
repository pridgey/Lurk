package app.lurk.web

import android.content.Context
import org.json.JSONObject
import org.json.JSONTokener

/** Loads the injected CSS/JS from assets/, so they can be edited without touching Kotlin. */
class Scripts(context: Context) {
    private val assets = context.applicationContext.assets

    private fun read(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    /** The skin injector with skin.css embedded as a JS string literal. */
    val skin: String by lazy {
        read("skin/inject.js").replace("__LURK_CSS__", JSONObject.quote(read("skin/skin.css")))
    }

    val whoami: String by lazy { read("engine/whoami.js") }
}

/**
 * evaluateJavascript hands back the script's result JSON-encoded. Our scripts return
 * JSON.stringify(...), so the raw value is a JSON string that itself contains JSON.
 */
fun decodeJsResult(raw: String?): JSONObject? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val inner = runCatching { JSONTokener(raw).nextValue() }.getOrNull() as? String ?: return null
    return runCatching { JSONObject(inner) }.getOrNull()
}

/** optString returns "null" for JSON null; this returns a real null (or null for blank). */
fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
