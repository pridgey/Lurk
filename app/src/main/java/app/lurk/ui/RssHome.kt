package app.lurk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lurk.data.FeedScreen

/** An RSS account without its feed link yet. */
@Composable
fun RssSignInView(onSignIn: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Reddit, through RSS", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Lurk reads your front page from its private RSS feed, and fills the bottom bar with the " +
                    "subreddits it sees there. Each subreddit loads from its own feed. RSS has posts only: " +
                    "comments and votes open in Chrome.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Add your feed link")
            }
        }
    }
}

/** Over an RSS account's feed: how fresh its posts are, and anything off about that. */
@Composable
fun RssFeedHeader(screen: FeedScreen) {
    val fetched = screen.fetchedAt ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(
            "Updated " + ago(fetched / 1000).let { if (it == "now") "just now" else "$it ago" } + " · comments open in Chrome",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        screen.notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
