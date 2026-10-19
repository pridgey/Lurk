package app.lurk.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.lurk.data.VideoStream

/**
 * Full-screen player for a Reddit video. Plays the HLS stream (with sound) and falls
 * back to the silent MP4 if that fails. GIF-style clips loop without controls.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(stream: VideoStream, onClose: () -> Unit, onOpenOnWeb: () -> Unit) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(stream.isGif) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = if (stream.isGif) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (stream.isGif) 0f else 1f
            setMediaItem(firstItem(stream))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        var onFallback = stream.hlsUrl == null
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val mp4 = stream.mp4Url
                if (!onFallback && mp4 != null) {
                    onFallback = true
                    player.setMediaItem(MediaItem.fromUri(mp4))
                    player.prepare()
                    player.play()
                } else {
                    failed = true
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Pause when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
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
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = !stream.isGif
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp),
            ) {
                RoundButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                Spacer(Modifier.weight(1f))
                if (!stream.isGif && !failed) {
                    RoundButton(onClick = {
                        muted = !muted
                        player.volume = if (muted) 0f else 1f
                    }) {
                        Icon(if (muted) LurkIcons.Muted else LurkIcons.Volume, if (muted) "Unmute" else "Mute", Modifier.size(22.dp))
                    }
                }
            }
            if (failed) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Text("Couldn't play this video.", color = Color.White)
                    FilledTonalButton(onClick = onOpenOnWeb) { Text("Open on web") }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
        ),
        modifier = Modifier.size(44.dp),
        content = content,
    )
}

private fun firstItem(stream: VideoStream): MediaItem {
    val hls = stream.hlsUrl
    return if (hls != null) {
        MediaItem.Builder().setUri(hls).setMimeType(MimeTypes.APPLICATION_M3U8).build()
    } else {
        MediaItem.fromUri(stream.mp4Url!!)
    }
}

/** "0:42", "12:05" */
fun duration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

