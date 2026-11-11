package app.lurk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.lurk.data.RssProbe
import app.lurk.data.TokenProbe

/**
 * Before Engine debug's RSS test: optionally the account's private front-page feed link,
 * from reddit.com/prefs/feeds. Blank runs the public-feeds test. The link is kept on this
 * phone for next time; clearing the field forgets it.
 */
@Composable
fun RssTestDialog(
    saved: String?,
    onRun: (frontPage: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(saved.orEmpty()) }
    val parsed = RssProbe.parseFrontPage(text)
    val blank = text.isBlank()

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onDismissRequest = onCancel,
        title = { Text("Test RSS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "To test your front page too, paste its private RSS link. In Chrome, open " +
                        "old.reddit.com/prefs/feeds and copy the RSS link next to \"your frontpage\". " +
                        "Leave it empty to test public feeds only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Front page RSS link") },
                    placeholder = { Text("https://www.reddit.com/.rss?feed=…&user=…") },
                    singleLine = true,
                    isError = !blank && parsed == null,
                    supportingText = {
                        when {
                            !blank && parsed == null -> Text("That isn't a front page feed link (needs feed= and user=).")
                            !blank -> Text("Kept on this phone. The token is hidden in the log.")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onRun(parsed) }, enabled = blank || parsed != null) {
                Text(if (blank) "Test public feeds" else "Run test")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Signs in a Reddit RSS account: its front page's private RSS feed link. [knownUser]: the
 * account being signed back in, if any.
 */
@Composable
fun RssLinkDialog(
    knownUser: String?,
    suggested: String?,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(suggested.orEmpty()) }
    val parsed = RssProbe.parseFrontPage(text)
    val blank = text.isBlank()

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onDismissRequest = onCancel,
        title = { Text(if (knownUser == null) "Add a Reddit RSS account" else "Sign in as u/$knownUser") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Lurk reads your front page from its private RSS feed, and learns your subreddits from it. " +
                        "In Chrome, open old.reddit.com/prefs/feeds and copy the RSS link next to \"your frontpage\". " +
                        "RSS has no scores or comments: posts open in Chrome for those.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Front page RSS link") },
                    placeholder = { Text("https://www.reddit.com/.rss?feed=…&user=…") },
                    singleLine = true,
                    isError = !blank && parsed == null,
                    supportingText = {
                        if (!blank && parsed == null) {
                            Text("That isn't a front page feed link (needs feed= and user=).")
                        } else {
                            Text("Kept on this phone. Anyone with it can read your front page.")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text("Sign in") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Before Engine debug's sign-in test: which Reddit login cookie to test. Blank uses the
 * account's own, from signing in on Reddit's site, when it has one. A pasted cookie is
 * used once and not kept.
 */
@Composable
fun TokenTestDialog(
    hasOwnCookie: Boolean,
    onRun: (cookie: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val parsed = TokenProbe.parseCookie(text)
    val blank = text.isBlank()

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onDismissRequest = onCancel,
        title = { Text("Test sign-in") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Checks whether a Reddit login still gets Lurk posts and comments after RSS ends. " +
                        "It signs in the way Reddit's own Android app does, which Reddit's terms don't allow, " +
                        "so a spare account is safest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (hasOwnCookie) {
                        "Leave this empty to use this account's sign-in, or paste another account's reddit_session cookie."
                    } else {
                        "This account isn't signed in on Reddit's site. On a computer, sign in to reddit.com, open the " +
                            "browser's developer tools, and copy the reddit_session cookie's value."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("reddit_session cookie") },
                    singleLine = true,
                    isError = !blank && parsed == null,
                    supportingText = {
                        if (!blank && parsed == null) Text("That doesn't look like a reddit_session cookie.")
                        else Text("Used for this test only. Never shown in the log.")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onRun(parsed) }, enabled = if (blank) hasOwnCookie else parsed != null) {
                Text(if (blank) "Use this account's" else "Run test")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
