package app.lurk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
            TextButton(onClick = { onRun(parsed) }, enabled = blank || parsed != null) {
                Text(if (blank) "Test public feeds" else "Run test")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Sets up Home from the account's private front-page RSS feed, for while Reddit blocks
 * Lurk's browser. [saved] is the link in use, if any; Remove goes back to Chrome.
 */
@Composable
fun RssLinkDialog(
    saved: String?,
    suggested: String?,
    onSave: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(saved ?: suggested.orEmpty()) }
    val parsed = RssProbe.parseFrontPage(text)
    val blank = text.isBlank()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Front page in Lurk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Lurk can show your Reddit front page from its private RSS feed. In Chrome, open " +
                        "old.reddit.com/prefs/feeds and copy the RSS link next to \"your frontpage\". " +
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
            TextButton(onClick = { onSave(parsed) }, enabled = parsed != null) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (saved != null) TextButton(onClick = { onSave(null) }) { Text("Remove") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}
