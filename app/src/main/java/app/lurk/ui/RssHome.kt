package app.lurk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
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
    InfoCard(
        icon = LurkIcons.Rss,
        title = "Reddit, through RSS",
        body = "Lurk reads your front page from its private RSS feed, and fills the bottom bar with the " +
            "subreddits it sees there. Each subreddit loads from its own feed. RSS has posts only: " +
            "comments and votes open in Chrome.",
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Add your feed link") }
    }
}

/** Over an RSS account's feed: how fresh its posts are, and anything off about that. */
@Composable
fun RssFeedHeader(screen: FeedScreen) {
    val fetched = screen.fetchedAt ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
            Text(
                "Updated " + ago(fetched / 1000).let { if (it == "now") "just now" else "$it ago" } + " · comments open in Chrome",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        screen.notice?.let {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(Modifier.padding(12.dp)) {
                    Icon(LurkIcons.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}
