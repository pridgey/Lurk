package app.lurk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.lurk.data.Actions
import app.lurk.data.Comment
import app.lurk.data.FeedScreen
import app.lurk.data.More
import app.lurk.data.ThreadScreen
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedView(
    screen: FeedScreen,
    actions: Actions,
    on: PostHandlers,
    onViewOnWeb: () -> Unit,
    /** Shown above the posts, e.g. where an RSS Home's posts come from. */
    header: (@Composable () -> Unit)? = null,
) {
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
        // The skeleton cards show the first load; the spinner is for pulls.
        isRefreshing = false,
        onRefresh = { screen.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (screen.posts.isEmpty()) {
            // A list even when empty, so there's something to pull on.
            LazyColumn(Modifier.fillMaxSize()) {
                header?.let { item(key = "header") { it() } }
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
                if (screen.loading && screen.error == null) {
                    item { Spacer(Modifier.height(6.dp)) }
                    items(3) { SkeletonCard() }
                }
            }
            return@PullToRefreshBox
        }
        LazyColumn(
            state = screen.listState,
            contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            header?.let { item(key = "header") { it() } }
            items(screen.posts, key = { it.name }) { post ->
                PostCard(post, screen.showsCommunity, actions, on)
            }
            item(key = "footer") {
                when {
                    screen.error != null -> Notice(screen.error!!, "Try again", { screen.loadMore() }, Modifier.fillMaxWidth())
                    screen.loading -> SkeletonCard()
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
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                    ) {
                        PostDetail(post, actions, on)
                    }
                }
            }
            if (screen.isSingleThread) {
                item(key = "single") {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            .clip(CircleShape)
                            .clickable { screen.post?.let { on.openThread(it) } },
                    ) {
                        Text(
                            "Single comment thread · View all",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            when {
                screen.error != null -> item(key = "error") {
                    Notice(screen.error!!, "Try again", { screen.load() }, Modifier.fillMaxWidth())
                }
                screen.loading && screen.items.isEmpty() -> item(key = "loading") { Loading() }
                !screen.loading && screen.items.isEmpty() && screen.post != null -> item(key = "empty") {
                    Notice("No comments yet. Be the first on the web.", modifier = Modifier.fillMaxWidth())
                }
            }
            item(key = "gap") { Spacer(Modifier.height(8.dp)) }
            itemsIndexed(visible, key = { _, it -> it.key }) { index, item ->
                when (item) {
                    is Comment -> CommentRow(
                        comment = item,
                        divider = index > 0,
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
                            if (!screen.expand(item, onMessage)) on.openRef(screen.continueRef(item))
                        },
                    )
                }
            }
            item(key = "end") { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/** Thread line colors by depth: soft, distinct hues. */
private val threadColors = listOf(
    Color(0xFFFF7A59), Color(0xFF4DA3FF), Color(0xFF2EC4B6),
    Color(0xFFB57BFF), Color(0xFFFFB020), Color(0xFFF06292),
)

/** Indents a row for its depth, with a rounded thread line on the left. */
@Composable
private fun Indented(depth: Int, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = (16 + (depth - 1).coerceAtLeast(0) * 14).dp)) {
        if (depth > 0) {
            Box(
                Modifier
                    .padding(vertical = 2.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(threadColors[(depth - 1) % threadColors.size].copy(alpha = 0.55f)),
            )
        }
        Box(Modifier.weight(1f).padding(start = if (depth > 0) 12.dp else 0.dp)) { content() }
    }
}

@Composable
private fun CommentRow(
    comment: Comment,
    /** Top-level comments get a divider above, except the first. */
    divider: Boolean,
    collapsed: Boolean,
    actions: Actions,
    onToggle: () -> Unit,
    onLink: (String) -> Unit,
    onUser: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        if (comment.depth == 0 && divider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Indented(comment.depth) {
            Column(
                Modifier.padding(
                    top = if (collapsed) 9.dp else 6.dp,
                    end = 8.dp,
                    bottom = if (collapsed) 11.dp else 2.dp,
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(comment.author, 22.dp, Modifier.clickable { onUser(comment.author) })
                    val authorColor = when (comment.distinguished) {
                        "moderator" -> Color(0xFF2EB67D)
                        "admin" -> Color(0xFFE5484D)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        comment.author,
                        style = MaterialTheme.typography.labelLarge,
                        color = authorColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .weight(1f, fill = false)
                            .clickable { onUser(comment.author) },
                    )
                    if (comment.isOp) {
                        Box(Modifier.padding(start = 6.dp)) { Tag("OP", MaterialTheme.colorScheme.primary) }
                    }
                    if (comment.stickied) {
                        Box(Modifier.padding(start = 6.dp)) { Tag("Pinned", MaterialTheme.colorScheme.tertiary) }
                    }
                    Text(
                        " · ${ago(comment.createdUtc)}" + if (collapsed) " · tap to expand" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!collapsed) VoteControls(comment.name, comment.score, comment.likes, actions, comment.scoreHidden)
                }
                if (!collapsed) {
                    CommentBody(
                        comment.bodyHtml,
                        comment.images,
                        onLink = onLink,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 30.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreRow(more: More, loading: Boolean, onClick: () -> Unit) {
    Indented(more.depth) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 4.dp, bottom = 10.dp)
                .clip(CircleShape)
                .clickable(enabled = !loading, onClick = onClick),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    when {
                        more.children.isEmpty() -> "Continue this thread"
                        more.count == 1 -> "1 more reply"
                        else -> "${more.count} more replies"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (loading) {
                    CircularProgressIndicator(Modifier.padding(start = 10.dp).size(14.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/**
 * Full-screen image with pinch to zoom. Tap to close. [preview], a smaller copy already
 * loaded in the list, shows at once and stays if the full image can't be loaded (on
 * Lemmy the original is often on another instance, which can be slow or gone).
 */
@Composable
fun ImageViewer(url: String, preview: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        var full by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClose)
                    .transformable(state)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null && full !is AsyncImagePainter.State.Success) {
                    AsyncImage(preview, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    onState = { full = it },
                    modifier = Modifier.fillMaxSize(),
                )
                when {
                    full is AsyncImagePainter.State.Loading && preview == null ->
                        CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                    full is AsyncImagePainter.State.Error && preview == null -> Text(
                        "Couldn't load this image",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            // The full image once it's in, else the copy on screen.
            val copyUrl = if (full is AsyncImagePainter.State.Success || preview == null) url else preview
            CopyImageButton(copyUrl, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
        }
    }
}

/** Copies the image to the clipboard. Android 13+ confirms copies itself; older versions get a toast. */
@Composable
private fun CopyImageButton(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copying by remember { mutableStateOf(false) }
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = !copying, onClickLabel = "Copy image") {
                copying = true
                scope.launch {
                    val ok = try {
                        ImageClipboard.copy(context, url)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    } finally {
                        copying = false
                    }
                    if (!ok) Toast.makeText(context, "Couldn't copy the image", Toast.LENGTH_SHORT).show()
                    else if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Image copied", Toast.LENGTH_SHORT).show()
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (copying) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(LurkIcons.Copy, "Copy image", Modifier.size(20.dp))
            }
        }
    }
}
