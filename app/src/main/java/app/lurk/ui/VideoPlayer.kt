package app.lurk.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.lurk.data.VideoStream

/** Which post's video is playing in place in a list. One at a time. */
@Stable
class InlineVideo {
    var playing by mutableStateOf<String?>(null)
}

/**
 * A player for one Reddit video, shared by the full-screen and in-list players. Plays the
 * HLS stream (with sound) and falls back to the silent MP4 if that fails. GIF-style clips
 * loop and start muted; if one turns out to have a sound track, [hasAudio] flips on.
 */
@Stable
class StreamPlayback(val player: ExoPlayer, stream: VideoStream) {
    var failed by mutableStateOf(false)
    var muted by mutableStateOf(stream.isGif)
    /** Regular videos always offer the mute button; GIFs only once a sound track shows up. */
    var hasAudio by mutableStateOf(!stream.isGif)
    var paused by mutableStateOf(false)

    fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
    }

    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
    }
}

/** A [StreamPlayback] that starts at [startMs], pauses in the background and is released with the caller. */
@Composable
fun rememberStreamPlayback(stream: VideoStream, startMs: Long = 0): StreamPlayback {
    val context = LocalContext.current
    val playback = remember(stream) {
        val player = ExoPlayer.Builder(context).build().apply {
            repeatMode = if (stream.isGif) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (stream.isGif) 0f else 1f
            setMediaItem(firstItem(stream), startMs)
            prepare()
            playWhenReady = true
        }
        StreamPlayback(player, stream)
    }

    DisposableEffect(playback) {
        val player = playback.player
        var onFallback = stream.hlsUrl == null
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val mp4 = stream.mp4Url
                if (!onFallback && mp4 != null) {
                    onFallback = true
                    player.setMediaItem(MediaItem.fromUri(mp4), player.currentPosition)
                    player.prepare()
                    player.play()
                } else {
                    playback.failed = true
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (stream.isGif) playback.hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playback.paused = !playWhenReady
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
    DisposableEffect(lifecycle, playback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) playback.player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return playback
}

/** Full-screen player for a Reddit video, starting at [startMs]. GIF-style clips loop without controls. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(stream: VideoStream, onClose: () -> Unit, onOpenOnWeb: () -> Unit, startMs: Long = 0) {
    val playback = rememberStreamPlayback(stream, startMs)

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = playback.player
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
                if (playback.hasAudio && !playback.failed) MuteButton(playback)
            }
            if (playback.failed) {
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

/**
 * A video playing in place in a list, fitted into the space its preview took ([modifier]
 * sets the size). Tap to pause; the buttons toggle sound and hand off to the full-screen
 * player at the current spot.
 */
@OptIn(UnstableApi::class)
@Composable
fun InlineVideoPlayer(stream: VideoStream, modifier: Modifier, onFullscreen: (positionMs: Long) -> Unit) {
    val playback = rememberStreamPlayback(stream)
    Box(modifier.background(Color.Black).clickable { playback.togglePause() }) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playback.player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (playback.failed) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            ) {
                Text("Couldn't play this video.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = { onFullscreen(0) }) { Text("Open full screen") }
            }
            return@Box
        }
        if (playback.paused) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(LurkIcons.Pause, "Paused", Modifier.padding(12.dp).size(30.dp))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            if (playback.hasAudio) MuteButton(playback, size = 36.dp)
            RoundButton(onClick = { onFullscreen(playback.player.currentPosition) }, size = 36.dp) {
                Icon(LurkIcons.Fullscreen, "Full screen", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MuteButton(playback: StreamPlayback, size: Dp = 44.dp) {
    RoundButton(onClick = { playback.toggleMute() }, size = size) {
        val muted = playback.muted
        Icon(if (muted) LurkIcons.Muted else LurkIcons.Volume, if (muted) "Unmute" else "Mute", Modifier.size(size / 2))
    }
}

@Composable
private fun RoundButton(onClick: () -> Unit, size: Dp = 44.dp, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
        ),
        modifier = Modifier.size(size),
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
