package app.lurk.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A bottom sheet showing a video page from another site. It looks for the page's main
 * video as it loads and, once found, shows only that. "Page" shows the whole page.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSheet(
    url: String,
    isolateScript: String,
    onClose: () -> Unit,
    onOpenOutside: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val openOut by rememberUpdatedState(onOpenOutside)
    var progress by remember { mutableIntStateOf(0) }
    /** Bumped on every page load, which restarts the search for the video. */
    var loads by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(true) }
    var videoOnly by remember { mutableStateOf(true) }

    val frame = remember {
        val web = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Sites serve their real player to Chrome, not to "WebView".
            app.lurk.web.lookLikeChrome(settings)
        }
        FrameLayout(context).apply {
            addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
    val web = frame.getChildAt(0) as WebView

    DisposableEffect(web) {
        var custom: View? = null
        var customCallback: WebChromeClient.CustomViewCallback? = null
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
            }

            // The site's own fullscreen button.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                custom?.let { frame.removeView(it) }
                custom = view
                customCallback = callback
                frame.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() {
                custom?.let { frame.removeView(it) }
                custom = null
                customCallback?.onCustomViewHidden()
                customCallback = null
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false // consent pages, redirects: stay
                // "Open in app" links (intent://, vnd.youtube:, ...) aren't followed in here.
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loads++
            }
        }
        web.loadUrl(url)
        onDispose {
            customCallback?.onCustomViewHidden()
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
    }

    // After each load, look for the video for a while: players often appear late.
    LaunchedEffect(loads) {
        if (loads == 0) return@LaunchedEffect
        found = false
        searching = true
        repeat(15) {
            val result = suspendCancellableCoroutine<String?> { cont ->
                web.evaluateJavascript(isolateScript) { cont.resume(it) }
            }
            if (result?.contains("found") == true) {
                found = true
                searching = false
                web.evaluateJavascript("window.__lurkIso && window.__lurkIso.set($videoOnly)", null)
                return@LaunchedEffect
            }
            delay(1_000)
        }
        searching = false
    }

    LaunchedEffect(videoOnly, found) {
        if (found) web.evaluateJavascript("window.__lurkIso && window.__lurkIso.set($videoOnly)", null)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> web.onPause()
                Lifecycle.Event.ON_RESUME -> web.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Uri.parse(url).host.orEmpty().removePrefix("www.").removePrefix("m."),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            found && videoOnly -> "Showing just the video"
                            found -> "Showing the whole page"
                            searching -> "Looking for the video…"
                            else -> "No video found; showing the page"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (found) {
                    ModeToggle(videoOnly, onChange = { videoOnly = it })
                }
                IconButton(onClick = { openOut(Uri.parse(web.url ?: url)) }) {
                    Icon(LurkIcons.External, "Open in browser", Modifier.size(20.dp))
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { (frame.parent as? ViewGroup)?.removeView(frame); frame },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    )
                }
            }
        }
    }
}

/** "Video | Page" segmented pill. */
@Composable
private fun ModeToggle(videoOnly: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(3.dp)) {
            listOf(true to "Video", false to "Page").forEach { (value, label) ->
                val selected = videoOnly == value
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape).clickable { onChange(value) },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
