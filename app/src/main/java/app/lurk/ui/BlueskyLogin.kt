package app.lurk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.lurk.data.BlueskyLogin
import app.lurk.data.BlueskyService
import app.lurk.data.NeedsEmailCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val APP_PASSWORDS_URL = "https://bsky.app/settings/app-passwords"

/**
 * Signs in to Bluesky with a handle and an app password (made in Bluesky's settings, so
 * Lurk never sees the real password). [knownHandle] fills in the handle when signing an
 * existing account back in. The password goes only to the account's PDS; Lurk keeps the
 * tokens it sends back.
 */
@Composable
fun BlueskyLoginDialog(
    knownHandle: String?,
    onSignedIn: (BlueskyLogin) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var handle by remember { mutableStateOf(knownHandle.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var needsCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val ready = handle.isNotBlank() && password.isNotEmpty() && (!needsCode || code.isNotBlank())

    fun submit() {
        if (!ready || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onSignedIn(BlueskyService.login(handle, password, code.takeIf { needsCode }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsEmailCode) {
                needsCode = true
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Couldn't sign in"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(if (knownHandle == null) "Add a Bluesky account" else "Sign in as @$knownHandle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Use an app password, not your main one. Make one in Bluesky under Settings › Privacy and security › App passwords.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { uriHandler.openUri(APP_PASSWORDS_URL) }, enabled = !busy) {
                    Text("Make an app password")
                }
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it },
                    label = { Text("Handle") },
                    placeholder = { Text("you.bsky.social") },
                    singleLine = true,
                    enabled = !busy && knownHandle == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("App password") },
                    placeholder = { Text("xxxx-xxxx-xxxx-xxxx") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (needsCode) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (needsCode) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.trim() },
                        label = { Text("Code from the email") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = ready && !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp).align(Alignment.CenterVertically), strokeWidth = 2.dp)
                } else {
                    Text("Sign in")
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
    )
}
