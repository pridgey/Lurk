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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lurk.data.VideoEmbed

/**
 * Full-screen player for a video from another site (YouTube, Vimeo, ...), using that
 * site's own embedded player in a small web view. Links out of the player open in
 * the site's app or a Custom Tab.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedPlayer(
    embed: VideoEmbed,
    siteName: String,
    onClose: () -> Unit,
    onOpenSite: () -> Unit,
    onExternalLink: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val openOut by rememberUpdatedState(onExternalLink)

    // The web view sits in a frame, so the site's own fullscreen button can lay its
    // video view over it.
    val frame = remember {
        val web = WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false // the tap that opened this counts
        }
        FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
    val web = frame.getChildAt(0) as WebView

    DisposableEffect(web) {
        var custom: View? = null
        var customCallback: WebChromeClient.CustomViewCallback? = null
        web.webChromeClient = object : WebChromeClient() {
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
            // The embed page itself loads as data; anything that navigates the whole
            // view away (e.g. "Watch on YouTube") goes out to the site instead.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                openOut(request.url)
                return true
            }
        }
        // An https base URL gives the embed a referrer, which YouTube requires. Reddit's
        // cookie-free embed domain, not reddit.com: on older WebViews this view shares the
        // account's cookie jar, and third-party HTML shouldn't run as Reddit itself.
        web.loadDataWithBaseURL("https://www.redditmedia.com/", page(embed), "text/html", "utf-8", null)
        onDispose {
            customCallback?.onCustomViewHidden()
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
    }

    // Pause when the app goes to the background.
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { (frame.parent as? ViewGroup)?.removeView(frame); frame },
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(top = 60.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp),
            ) {
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.size(44.dp),
                ) { Icon(Icons.Default.Close, "Close") }
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenSite),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text("Open in $siteName", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Icon(LurkIcons.External, null, Modifier.padding(start = 6.dp).size(16.dp))
                    }
                }
            }
        }
    }
}

/** A black page with the site's iframe sized to the screen width at the video's shape. */
private fun page(embed: VideoEmbed): String {
    val ratio = if (embed.width > 0 && embed.height > 0) embed.height.toFloat() / embed.width else 9f / 16f
    val iframe = embed.html
        .replaceFirst("<iframe", "<iframe allow=\"autoplay; fullscreen; encrypted-media; picture-in-picture\" allowfullscreen")
        .let(::withAutoplay)
    return """
        <!doctype html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body { margin: 0; height: 100%; background: #000; }
          body { display: flex; align-items: center; justify-content: center; }
          iframe { width: 100vw !important; height: ${"%.3f".format(ratio * 100)}vw !important; max-height: 100vh; border: 0; }
        </style>
        </head><body>$iframe</body></html>
    """.trimIndent()
}

/** Asks YouTube embeds to start playing right away (the tap that opened this counts). */
private fun withAutoplay(html: String): String =
    Regex("""src="(https://www\.youtube(?:-nocookie)?\.com/embed/[^"]*)"""").replace(html) { m ->
        val src = m.groupValues[1]
        "src=\"" + src + (if ('?' in src) "&" else "?") + "autoplay=1&playsinline=1\""
    }
