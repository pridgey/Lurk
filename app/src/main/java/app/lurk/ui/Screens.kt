package app.lurk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.lurk.data.Actions
import app.lurk.data.Comment
import app.lurk.data.FeedScreen
import app.lurk.data.More
import app.lurk.data.ThreadScreen
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedView(screen: FeedScreen, actions: Actions, on: PostHandlers, onViewOnWeb: () -> Unit) {
    // Load the next page when the list gets near its end.
    LaunchedEffect(screen) {
        snapshotFlow {
            val info = screen.listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { if (screen.error == null) screen.loadMore() }
    }

    PullToRefreshBox(
        isRefreshing = screen.loading && screen.posts.isEmpty(),
        onRefresh = { screen.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (screen.posts.isEmpty()) {
            // A list even when empty, so there's something to pull on.
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    val error = screen.error
                    when {
                        error != null -> Column(
                            Modifier.fillMaxWidth().padding(top = 96.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Notice(error, "Try again", { screen.refresh() }, Modifier.fillMaxWidth())
                            TextButton(onClick = onViewOnWeb) { Text("View on web") }
                        }
                        !screen.loading -> Notice("Nothing here.", modifier = Modifier.fillMaxWidth().padding(top = 96.dp))
                    }
                }
            }
            return@PullToRefreshBox
        }
        LazyColumn(state = screen.listState, modifier = Modifier.fillMaxSize()) {
            items(screen.posts, key = { it.name }) { post ->
                PostCard(post, screen.showsSubreddit, actions, on)
                HorizontalDivider()
            }
            item(key = "footer") {
                when {
                    screen.error != null -> Notice(screen.error!!, "Try again", { screen.loadMore() }, Modifier.fillMaxWidth())
                    screen.loading -> Loading()
                    screen.endReached -> Notice("That's everything.", modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadView(
    screen: ThreadScreen,
    actions: Actions,
    on: PostHandlers,
    onMessage: (String) -> Unit,
) {
    val visible by remember(screen) { derivedStateOf { screen.visibleItems() } }
    PullToRefreshBox(
        isRefreshing = screen.loading && screen.items.isNotEmpty(),
        onRefresh = { screen.load() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = screen.listState, modifier = Modifier.fillMaxSize()) {
            screen.post?.let { post ->
                item(key = "post") {
                    PostDetail(post, actions, on)
                    HorizontalDivider()
                }
            }
            if (screen.isSingleThread) {
                item(key = "single") {
                    Text(
                        "You're viewing a single comment's thread. View all comments",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { screen.post?.let { on.openThread(it) } }
                            .padding(16.dp),
                    )
                    HorizontalDivider()
                }
            }
            when {
                screen.error != null -> item(key = "error") {
                    Notice(screen.error!!, "Try again", { screen.load() }, Modifier.fillMaxWidth())
                }
                screen.loading && screen.items.isEmpty() -> item(key = "loading") { Loading() }
                !screen.loading && screen.items.isEmpty() && screen.post != null -> item(key = "empty") {
                    Notice("No comments yet.", modifier = Modifier.fillMaxWidth())
                }
            }
            items(visible, key = { it.key }) { item ->
                when (item) {
                    is Comment -> CommentRow(
                        comment = item,
                        collapsed = screen.collapsed.containsKey(item.name),
                        actions = actions,
                        onToggle = { screen.toggle(item) },
                        onLink = on.openLink,
                        onUser = on.openUser,
                    )
                    is More -> MoreRow(
                        more = item,
                        loading = screen.loadingMore.containsKey(item.key),
                        onClick = {
                            if (!screen.expand(item, onMessage)) on.openLink(screen.continueUrl(item))
                        },
                    )
                }
            }
            item(key = "end") { Spacer(Modifier.height(48.dp)) }
        }
    }
}

private val threadColors = listOf(
    Color(0xFF4F86F7), Color(0xFF2EB67D), Color(0xFFE8A33D),
    Color(0xFFD9534F), Color(0xFF9B59B6), Color(0xFF16A2B8),
)

/** Indents a row for its depth, drawing the thread line on the left. */
@Composable
private fun Indented(depth: Int, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = (8 + depth * 12).dp)) {
        if (depth > 0) {
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(threadColors[(depth - 1) % threadColors.size].copy(alpha = 0.6f)),
            )
        }
        Box(Modifier.weight(1f).padding(start = 8.dp)) { content() }
    }
}

@Composable
private fun CommentRow(
    comment: Comment,
    collapsed: Boolean,
    actions: Actions,
    onToggle: () -> Unit,
    onLink: (String) -> Unit,
    onUser: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        if (comment.depth == 0) HorizontalDivider()
        Indented(comment.depth) {
            Column(Modifier.padding(top = 6.dp, end = 4.dp, bottom = if (collapsed) 6.dp else 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val authorColor = when {
                        comment.distinguished == "moderator" -> Color(0xFF2EB67D)
                        comment.distinguished == "admin" -> Color(0xFFD9534F)
                        comment.isOp -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        comment.author + if (comment.isOp) " (OP)" else "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = authorColor,
                        modifier = Modifier.clickable { onUser(comment.author) },
                    )
                    Text(
                        buildString {
                            append(" · ${ago(comment.createdUtc)}")
                            if (comment.stickied) append(" · pinned")
                            if (collapsed) append(" · collapsed")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (!collapsed) {
                        VoteControls(comment.name, comment.score, comment.likes, actions, comment.scoreHidden, small = true)
                    }
                }
                if (!collapsed) {
                    HtmlText(
                        comment.bodyHtml,
                        onLink = onLink,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreRow(more: More, loading: Boolean, onClick: () -> Unit) {
    Indented(more.depth) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(enabled = !loading, onClick = onClick).padding(vertical = 10.dp),
        ) {
            Text(
                when {
                    more.children.isEmpty() -> "Continue this thread →"
                    more.count == 1 -> "1 more reply"
                    else -> "${more.count} more replies"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (loading) {
                CircularProgressIndicator(Modifier.padding(start = 12.dp).size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
    }
}

/** Full-screen image with pinch to zoom. Tap to close. */
@Composable
fun ImageViewer(url: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClose)
                    .transformable(state)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}
