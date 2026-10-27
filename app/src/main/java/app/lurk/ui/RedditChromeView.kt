package app.lurk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lurk.data.RedditAccess

/**
 * Reddit while it blocks Lurk's own browser: a launcher that opens Reddit pages in
 * Chrome tabs, where Reddit lets you in with Chrome's sign-in.
 */
@Composable
fun RedditChromeView(
    /** Cached from the last sync that worked; may be empty. */
    subreddits: List<String>,
    onOpen: (url: String) -> Unit,
    onRetry: () -> Unit,
    /** Set up Home from the front page's RSS feed. */
    onSetUpRss: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "intro") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Reddit opens in Chrome", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Reddit blocks Lurk's built-in browser, so Lurk can't show Reddit itself right now. " +
                            "Pages open in a Chrome tab instead, signed in as whoever is signed in to Reddit in Chrome. " +
                            "Lemmy accounts work as usual.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { onOpen(RedditAccess.Urls.HOME) },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) { Text("Open Reddit") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        TextButton(onClick = { onOpen(RedditAccess.Urls.community("popular")) }) { Text("Popular") }
                        TextButton(onClick = { onOpen(RedditAccess.Urls.community("all")) }) { Text("All") }
                        TextButton(onClick = { onOpen(RedditAccess.Urls.COMMUNITIES) }) { Text("Discover") }
                    }
                }
            }
        }
        item(key = "rss") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Your front page in Lurk", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Lurk can still show your front page's posts, from Reddit's RSS feed. " +
                            "Comments and votes stay in Chrome.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onSetUpRss, modifier = Modifier.padding(top = 4.dp)) { Text("Set it up") }
                }
            }
        }
        if (subreddits.isNotEmpty()) {
            item(key = "header") {
                Text(
                    "Your subreddits",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(subreddits, key = { "s:" + it.lowercase() }) { name ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpen(RedditAccess.Urls.community(name)) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Avatar(name, 32.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("r/$name", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item(key = "retry") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 24.dp, end = 24.dp),
            ) {
                Text(
                    "Reddit may lift the block later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text("Try Lurk's browser again") }
            }
        }
    }
}

