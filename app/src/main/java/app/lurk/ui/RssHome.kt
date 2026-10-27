package app.lurk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lurk.data.FeedScreen

/**
 * Over an RSS Home: where the posts come from, how fresh they are, and the way to change
 * the feed link or try Lurk's own browser again.
 */
@Composable
fun RssHomeHeader(screen: FeedScreen, onFeedLink: () -> Unit, onRetryBrowser: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            val fetched = screen.fetchedAt
            Text(
                "From your front page's RSS feed" +
                    (fetched?.let { " · updated " + ago(it / 1000).let { a -> if (a == "now") "just now" else "$a ago" } } ?: ""),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Reddit is blocking Lurk's browser. Comments, votes and subreddits open in Chrome.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            screen.notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp, end = 8.dp),
                )
            }
            Row {
                TextButton(onClick = onFeedLink) { Text("Feed link") }
                TextButton(onClick = onRetryBrowser) { Text("Try Lurk's browser") }
            }
        }
    }
}
