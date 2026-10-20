package app.lurk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private const val MIN_PIN = 4
private const val MAX_PIN = 8
private const val FREE_TRIES = 5
private const val WAIT_SECONDS = 30

/** Shown instead of a PIN-locked account until its PIN is entered. */
@Composable
fun LockScreen(
    /** Null when there's no other account to go to. */
    onLeave: (() -> Unit)?,
    onUnlock: (String) -> Boolean,
    onForget: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var failures by remember { mutableIntStateOf(0) }
    var waitUntil by remember { mutableLongStateOf(0L) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var confirmForget by remember { mutableStateOf(false) }

    // Count down after too many wrong tries.
    LaunchedEffect(waitUntil) {
        while (true) {
            secondsLeft = ((waitUntil - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
            if (secondsLeft == 0) break
            delay(250)
        }
    }

    fun submit() {
        if (secondsLeft > 0 || pin.length < MIN_PIN) return
        if (onUnlock(pin)) return
        pin = ""
        failures++
        if (failures % FREE_TRIES == 0) {
            waitUntil = System.currentTimeMillis() + WAIT_SECONDS * 1000L
            message = null
        } else {
            message = "Wrong PIN"
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        ) {
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Lock, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(
                "Enter the PIN for this private account",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    secondsLeft > 0 -> "Too many tries. Wait ${secondsLeft}s."
                    else -> message ?: " "
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(16.dp))
            PinPad(
                pin = pin,
                enabled = secondsLeft == 0,
                onChange = { pin = it; message = null },
                onDone = ::submit,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onLeave != null) TextButton(onClick = onLeave) { Text("Use another account") }
                TextButton(onClick = { confirmForget = true }) { Text("Forgot PIN?") }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Remove this account?") },
            text = {
                Text("Without the PIN, the only way back in is to remove the account from Lurk and sign in to it again.")
            },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForget() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

/** Choose a PIN, then type it again to confirm. */
@Composable
fun SetPinDialog(username: String?, onSet: (String) -> Unit, onCancel: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            ) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.Lock, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (first == null) "Choose a PIN for ${username?.let { "u/$it" } ?: "this account"}" else "Enter it again",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    message ?: "$MIN_PIN to $MAX_PIN digits. You'll need it to switch to this account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                PinPad(
                    pin = pin,
                    enabled = true,
                    onChange = { pin = it; message = null },
                    onDone = {
                        val chosen = first
                        when {
                            pin.length < MIN_PIN -> message = "Use at least $MIN_PIN digits"
                            chosen == null -> { first = pin; pin = "" }
                            chosen == pin -> onSet(pin)
                            else -> { first = null; pin = ""; message = "Those didn't match. Try again." }
                        }
                    },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** Dots for the digits so far, and a number pad with backspace and done. */
@Composable
private fun PinPad(pin: String, enabled: Boolean, onChange: (String) -> Unit, onDone: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.height(16.dp)) {
        repeat(maxOf(pin.length, MIN_PIN)) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        if (i < pin.length) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
    Spacer(Modifier.height(32.dp))
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("back", "0", "done"))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (key in row) {
                    val keyEnabled = enabled && when (key) {
                        "back" -> pin.isNotEmpty()
                        "done" -> pin.length >= MIN_PIN
                        else -> pin.length < MAX_PIN
                    }
                    Surface(
                        shape = CircleShape,
                        color = when (key) {
                            "done" -> if (keyEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
                            "back" -> MaterialTheme.colorScheme.background
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = when {
                            key == "done" && keyEnabled -> MaterialTheme.colorScheme.onPrimary
                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .clickable(enabled = keyEnabled) {
                                when (key) {
                                    "back" -> onChange(pin.dropLast(1))
                                    "done" -> onDone()
                                    else -> onChange(pin + key)
                                }
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when (key) {
                                "back" -> Icon(Icons.AutoMirrored.Filled.ArrowBack, "Delete")
                                "done" -> Icon(Icons.Default.Check, "Done")
                                else -> Text(key, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
