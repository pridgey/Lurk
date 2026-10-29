package app.lurk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lurk.data.FeedScreen
import app.lurk.data.Game
import app.lurk.data.GameTeam
import app.lurk.data.GameThreads
import app.lurk.data.GamesScreen
import app.lurk.data.Post

private val CardShape = RoundedCornerShape(24.dp)

/** A game thread posted this recently, with no post-game thread yet, is likely still being played. */
private const val LIVE_SECONDS = 5 * 3600L

/** A community's games, a day at a time: teams, scores, and a tap to each game's threads. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesView(screen: GamesScreen, on: PostHandlers) {
    val day = screen.day
    PullToRefreshBox(
        isRefreshing = screen.loading && screen.loaded,
        onRefresh = { screen.load(refresh = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = screen.listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (screen.days.size > 1) {
                item(key = "days") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        items(screen.days, key = { it.date.toString() }) { d ->
                            DayChip(GameThreads.dayLabel(d.date), selected = d.date == day?.date) { screen.selected = d.date }
                        }
                    }
                }
            }
            item(key = "status") { StatusLine(screen) }

            val error = screen.error
            when {
                !screen.loaded && error == null -> item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        screen.loadingNote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            )
                        }
                    }
                }
                error != null && screen.days.isEmpty() -> item(key = "error") {
                    Notice(error, "Try again", { screen.load(refresh = true) }, Modifier.fillMaxWidth().padding(top = 48.dp))
                }
                day == null -> item(key = "empty") {
                    Notice(
                        "No game or match threads here in the last week.",
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
            }

            if (day != null) {
                // Leagues only help when the day mixes them (r/soccer); r/nba's are all the same.
                val showLeague = day.games.mapNotNull { it.league }.distinct().size > 1
                items(day.games, key = { it.key }) { game -> GameCard(game, showLeague, on) }
                if (day.others.isNotEmpty()) {
                    item(key = "others") {
                        Text(
                            "Other threads",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(day.others, key = { it.name }) { post -> OtherThread(post, on) }
                }
            }
        }
    }
}

/** How many games, how many are over, and (RSS) how fresh the list is. */
@Composable
private fun StatusLine(screen: GamesScreen) {
    val day = screen.day
    val parts = buildList {
        if (day != null && day.games.isNotEmpty()) {
            val finals = day.games.count { it.isFinal }
            add("${day.games.size} ${if (day.games.size == 1) "game" else "games"}")
            if (finals < day.games.size) add("$finals final")
        }
        screen.fetchedAt?.let { add("updated " + ago(it / 1000).let { a -> if (a == "now") "just now" else "$a ago" }) }
    }
    val problem = screen.notice ?: screen.error?.takeIf { screen.days.isNotEmpty() }
    if (parts.isEmpty() && problem == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString(" · ").replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.height(36.dp).clip(CircleShape).clickable(onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** One game: its status, both teams with records and scores, and buttons for its two threads. */
@Composable
private fun GameCard(game: Game, showLeague: Boolean, on: PostHandlers) {
    val main = game.postGame ?: game.gameThread
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(CardShape)
            .then(if (main != null) Modifier.clickable { on.openThread(main) } else Modifier),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val live = !game.isFinal &&
                    (game.gameThread?.createdUtc ?: 0L) > System.currentTimeMillis() / 1000 - LIVE_SECONDS
                Text(
                    when {
                        game.isFinal -> listOfNotNull("Final", game.note).joinToString(" · ")
                        live -> "Game thread open"
                        else -> "No result yet"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (showLeague) {
                    game.league?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TeamRow(game.first, won = game.winner == 0, lost = game.winner == 1)
            Spacer(Modifier.height(8.dp))
            TeamRow(game.second, won = game.winner == 1, lost = game.winner == 0)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                ThreadButton("Game thread", game.gameThread, tonal = false, on, Modifier.weight(1f))
                ThreadButton("Post game", game.postGame, tonal = true, on, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TeamRow(team: GameTeam, won: Boolean, lost: Boolean) {
    val faded = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(team.name, 32.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                team.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (won) FontWeight.Bold else FontWeight.Normal,
                color = if (lost) faded else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            team.record?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = faded)
            }
        }
        team.score?.let {
            Text(
                it.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (won) FontWeight.Bold else FontWeight.Normal,
                color = if (lost) faded else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** A button to a game's thread, with its comment count where the site gives one; disabled until the thread exists. */
@Composable
private fun ThreadButton(label: String, post: Post?, tonal: Boolean, on: PostHandlers, modifier: Modifier) {
    val text = if (post != null && post.hasStats && post.numComments > 0) "$label · ${compact(post.numComments)}" else label
    val onClick = { post?.let(on.openThread) ?: Unit }
    val content: @Composable () -> Unit = {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (tonal) {
        FilledTonalButton(onClick = onClick, enabled = post != null, modifier = modifier) { content() }
    } else {
        OutlinedButton(onClick = onClick, enabled = post != null, modifier = modifier) { content() }
    }
}

/** A thread that isn't one game's, like r/nba's daily game thread index. */
@Composable
private fun OtherThread(post: Post, on: PostHandlers) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { on.openThread(post) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(LurkIcons.Comment, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(post.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Over a community's feed when it has game threads in it: a way into the scoreboard.
 * Shows nothing for feeds without them.
 */
@Composable
fun ScoreboardBanner(screen: FeedScreen, onOpen: () -> Unit) {
    val any by remember(screen) { derivedStateOf { screen.posts.any { GameThreads.isGameThread(it.title) } } }
    if (!any) return
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(CardShape)
            .clickable(onClick = onOpen),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Icon(LurkIcons.Scores, contentDescription = null)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Scoreboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Scores and game threads, by day", style = MaterialTheme.typography.bodySmall)
            }
            Icon(LurkIcons.Chevron, contentDescription = null)
        }
    }
}
