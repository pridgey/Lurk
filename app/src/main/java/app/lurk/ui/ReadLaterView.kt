package app.lurk.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lurk.data.Actions
import app.lurk.data.LaterPost
import app.lurk.data.ReadLater
import app.lurk.data.ReadLaterScreen

/**
 * Read later: posts put aside, newest first. Opening one takes it off the shelf; swiping
 * either way takes it off without opening it. Both can be undone from the snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadLaterView(
    screen: ReadLaterScreen,
    shelf: ReadLater,
    actions: Actions,
    on: PostHandlers,
    /** Opens [LaterPost] and takes it off, offering Undo. */
    onOpen: (LaterPost) -> Unit,
    /** Took [LaterPost] off by swiping, offering Undo. */
    onRemoved: (LaterPost) -> Unit,
    onClear: () -> Unit,
) {
    val items = shelf.items
    if (items.isEmpty()) {
        EmptyState(
            icon = LurkIcons.Clock,
            title = "Nothing saved for later",
            body = "Tap the clock on any post to keep it here. It stays on this phone, and the site never knows.",
            modifier = Modifier.padding(top = 64.dp),
        )
        return
    }
    // Opening a post goes through onOpen, which takes it off the shelf first.
    val handlers = PostHandlers(
        openThread = { post -> items.firstOrNull { it.post.name == post.name }?.let(onOpen) ?: on.openThread(post) },
        openLink = on.openLink,
        openImage = on.openImage,
        openCommunity = on.openCommunity,
        openUser = on.openUser,
        openRef = on.openRef,
        openOnWeb = on.openOnWeb,
        openVideo = on.openVideo,
        share = on.share,
        inlineVideo = on.inlineVideo,
        openArticle = on.openArticle,
        openReader = on.openReader,
        toggleLater = on.toggleLater,
        isLater = on.isLater,
    )
    LazyColumn(
        state = screen.listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "head") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    if (items.size == 1) "1 post · Swipe to remove" else "${items.size} posts · Swipe to remove",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Clear all") }
            }
        }
        items(items, key = { it.post.name }) { item ->
            val state = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled) onRemoved(item)
                    value != SwipeToDismissBoxValue.Settled
                },
            )
            SwipeToDismissBox(
                state = state,
                backgroundContent = {
                    val active = state.targetValue != SwipeToDismissBoxValue.Settled
                    val color by animateColorAsState(
                        if (active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
                        label = "dismiss",
                    )
                    val start = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (start) Arrangement.Start else Arrangement.End,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                            .clip(PostCardShape)
                            .background(color)
                            .padding(horizontal = 28.dp),
                    ) {
                        Icon(
                            LurkIcons.Trash, "Remove",
                            tint = if (active) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            ) {
                Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                    PostCard(item.post, showCommunity = true, actions = actions, on = handlers)
                }
            }
        }
    }
}
