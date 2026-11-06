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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
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
    /** Add a Reddit RSS account. */
    onSetUpRss: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "intro") {
            InfoCard(
                icon = LurkIcons.Chrome,
                title = "Reddit opens in Chrome",
                body = "Reddit blocks Lurk's built-in browser, so Lurk can't show Reddit itself right now. " +
                    "Pages open in a Chrome tab instead, signed in as whoever is signed in to Reddit in Chrome. " +
                    "Lemmy accounts work as usual.",
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Button(onClick = { onOpen(RedditAccess.Urls.HOME) }, modifier = Modifier.fillMaxWidth()) { Text("Open Reddit") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onOpen(RedditAccess.Urls.community("popular")) }, modifier = Modifier.weight(1f)) { Text("Popular") }
                    OutlinedButton(onClick = { onOpen(RedditAccess.Urls.community("all")) }, modifier = Modifier.weight(1f)) { Text("All") }
                    OutlinedButton(onClick = { onOpen(RedditAccess.Urls.COMMUNITIES) }, modifier = Modifier.weight(1f)) { Text("Discover") }
                }
            }
        }
        item(key = "rss") {
            InfoCard(
                icon = LurkIcons.Rss,
                title = "Read Reddit in Lurk",
                body = "A Reddit RSS account shows your front page and subreddits from Reddit's RSS feeds. " +
                    "Comments and votes stay in Chrome.",
                modifier = Modifier.padding(top = 12.dp),
            ) {
                FilledTonalButton(onClick = onSetUpRss, modifier = Modifier.fillMaxWidth()) { Text("Add a Reddit RSS account") }
            }
        }
        if (subreddits.isNotEmpty()) {
            item(key = "header") { SectionLabel("Your subreddits", Modifier.padding(top = 8.dp)) }
            item(key = "subs") {
                RowGroup {
                    subreddits.forEachIndexed { i, name ->
                        if (i > 0) GroupDivider()
                        GroupRow(
                            title = "r/$name",
                            leading = { Avatar(name, 34.dp) },
                            trailing = { Icon(LurkIcons.External, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                            onClick = { onOpen(RedditAccess.Urls.community(name)) },
                        )
                    }
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

