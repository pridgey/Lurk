package app.lurk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.lurk.data.CollectionLog
import app.lurk.data.parseSubreddits
import kotlinx.coroutines.delay

/** What background collection has been doing, newest first. Updates while it's open. */
@Composable
fun CollectionLogDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var entries by remember { mutableStateOf(CollectionLog.read(context).asReversed()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            entries = CollectionLog.read(context).asReversed()
        }
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onDismissRequest = onClose,
        title = { Text("Collection log") },
        text = {
            if (entries.isEmpty()) {
                Text(
                    "Nothing yet. Android runs the first check within about 30 minutes of adding the account, sometimes later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(entries) { entry ->
                            Text(
                                CollectionLog.line(entry),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        CollectionLog.clear(context)
                        entries = emptyList()
                    },
                    enabled = entries.isNotEmpty(),
                ) { Text("Clear") }
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(entries.joinToString("\n", transform = CollectionLog::line))) },
                    enabled = entries.isNotEmpty(),
                ) { Text("Copy") }
            }
        },
    )
}

/** Picks the subreddits the collected account gathers, and when it may use mobile data. */
@Composable
fun CollectedDialog(
    subreddits: List<String>,
    cellularOnWifi: Boolean,
    onSave: (List<String>, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(subreddits.joinToString(", ")) }
    var onWifi by remember { mutableStateOf(cellularOnWifi) }
    val parsed = parseSubreddits(text)

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        onDismissRequest = onCancel,
        title = { Text("Collected subreddits") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "No Reddit account needed. A few times a day, Lurk saves new posts from these subreddits " +
                        "over mobile data and keeps them for two weeks. RSS has no scores or comments.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Subreddits") },
                    placeholder = { Text("pics, Kotlin, androiddev") },
                    supportingText = { Text("${parsed.size} subreddit${if (parsed.size == 1) "" else "s"}") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Also collect while on wifi", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Still uses mobile data, so it costs some data and battery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = onWifi, onCheckedChange = { onWifi = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(parsed, onWifi) }, enabled = parsed.isNotEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
