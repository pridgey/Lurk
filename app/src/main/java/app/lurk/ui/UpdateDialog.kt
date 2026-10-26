package app.lurk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.lurk.data.Release
import app.lurk.data.UpdateState
import app.lurk.data.Updater

/** What's new in the latest build, and downloading and installing it. */
@Composable
fun UpdateDialog(updater: Updater, onClose: () -> Unit) {
    val state = updater.state
    // Set while the person is in system settings allowing Lurk to install apps.
    var awaitingPermission by remember { mutableStateOf<Release?>(null) }

    fun start(release: Release) {
        if (!updater.canInstall()) {
            awaitingPermission = release
            return
        }
        updater.startInstall(release)
    }

    // Back from settings: carry on if the permission was granted.
    @Suppress("DEPRECATION")
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val release = awaitingPermission
            if (event == Lifecycle.Event.ON_RESUME && release != null) {
                awaitingPermission = null
                start(release)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    AlertDialog(
        onDismissRequest = onClose, // a download carries on; the ⋮ menu shows its progress
        title = {
            Text(
                when (state) {
                    is UpdateState.Checking -> "Checking for updates…"
                    is UpdateState.UpToDate -> "Lurk is up to date"
                    is UpdateState.Failed -> if (state.release != null) "Update didn't finish" else "Couldn't check for updates"
                    else -> updater.available?.let { "Build ${it.build} is ready" } ?: "Updates"
                },
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (state) {
                    is UpdateState.Downloading -> {
                        val p = state.progress
                        Text(if (p == null) "Downloading…" else "Downloading… ${(p * 100).toInt()}%")
                        if (p == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        } else {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        }
                    }
                    is UpdateState.Installing -> {
                        Text("Confirm the update in Android's installer. Lurk restarts on the new build.")
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    is UpdateState.Failed -> Text(state.message)
                    is UpdateState.UpToDate -> Text("You're on build ${updater.installedBuild}, the newest one.")
                    is UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Unit
                }
                if (awaitingPermission != null) {
                    Text(
                        "Allow Lurk to install apps on the page that opened, then come back here.",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                val release = updater.available
                if (release != null && state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                    Text(
                        "You have build ${updater.installedBuild}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = if (state is UpdateState.Failed) 12.dp else 0.dp),
                    )
                    if (release.changes.isNotEmpty()) {
                        Text(
                            "Recent changes",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                            release.changes.forEach { line ->
                                Text("• $line", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val release = updater.available
            when {
                busy -> TextButton(onClick = onClose) { Text("Hide") }
                release != null -> TextButton(onClick = { start(release) }) {
                    Text(if (state is UpdateState.Failed) "Try again" else "Download and install")
                }
                state is UpdateState.Failed -> TextButton(onClick = { updater.startCheck() }) { Text("Try again") }
                else -> TextButton(onClick = onClose) { Text("OK") }
            }
        },
        dismissButton = {
            if (!busy && (updater.available != null || state is UpdateState.Failed)) {
                TextButton(onClick = onClose) { Text("Later") }
            }
        },
    )
}
