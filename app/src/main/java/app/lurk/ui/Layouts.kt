package app.lurk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lurk.data.Actions
import app.lurk.data.Post
import app.lurk.data.PostLayout
import app.lurk.data.PostMedia
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** The picture that stands for a post in small places: its image, gallery's first, video's still, or link thumbnail. */
fun Post.coverUrl(): String? = when (val m = media) {
    is PostMedia.Image -> m.picture.url
    is PostMedia.Gallery -> m.pictures.firstOrNull()?.url
    is PostMedia.Video -> m.preview?.url
    null -> thumbnail
}

/** The cover's shape, width over height, when known. */
private fun Post.coverAspect(): Float? = when (val m = media) {
    is PostMedia.Image -> m.picture.aspect
    is PostMedia.Gallery -> m.pictures.firstOrNull()?.aspect
    is PostMedia.Video -> m.preview?.aspect
    null -> null
}

/**
 * A post as one dense row: where it's from and when, the title, a line of numbers, and a
 * thumbnail on the right. Tapping opens the thread; the thumbnail opens the media itself.
 */
@Composable
fun CompactPostRow(post: Post, showCommunity: Boolean, actions: Actions, on: PostHandlers) {
    val naming = LocalNaming.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val showNsfw = LocalShowNsfw.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { on.openThread(post) },
    ) {
        Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 10.dp)) {
            Column(Modifier.weight(1f)) {
                val community = post.community?.takeIf { showCommunity }
                Text(
                    buildString {
                        append(if (community != null) naming.community(community) else naming.user(post.author))
                        append(" · ").append(ago(post.createdUtc))
                        if (!post.isSelf && post.media == null) append(" · ").append(post.domain.removePrefix("www."))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    post.title.ifBlank { post.selftext },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    if (post.over18) {
                        Tag("NSFW", NsfwColor)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (post.hasStats) {
                        val score = actions.score(post.name, post.score, post.likes)
                        Icon(LurkIcons.Upvote, null, tint = muted, modifier = Modifier.size(14.dp))
                        Text(compact(score), style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(start = 3.dp))
                        Spacer(Modifier.width(12.dp))
                        Icon(LurkIcons.Comment, null, tint = muted, modifier = Modifier.size(13.dp))
                        Text(compact(post.numComments), style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(start = 4.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    LaterButton(post, on)
                }
            }
            val cover = post.coverUrl()?.takeIf { (!post.over18 || showNsfw) && !post.spoiler }
            if (cover != null || post.media != null || !post.isSelf) {
                Spacer(Modifier.width(12.dp))
                CompactThumb(post, cover, on)
            }
        }
    }
}

@Composable
private fun CompactThumb(post: Post, cover: String?, on: PostHandlers) {
    val media = post.media
    val open: () -> Unit = when (media) {
        is PostMedia.Image -> ({ on.openImage(media.picture.fullUrl, media.picture.url) })
        is PostMedia.Video -> ({ on.openVideo(post, 0) })
        is PostMedia.Gallery -> ({ on.openThread(post) })
        null -> ({ on.openArticle(post) })
    }
    Box(
        Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = open),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when (media) {
                null -> LurkIcons.External
                else -> LurkIcons.Photo
            },
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        val badge: Pair<ImageVector, String?>? = when (media) {
            is PostMedia.Video -> LurkIcons.Pause to null
            is PostMedia.Gallery -> LurkIcons.Images to media.pictures.size.toString()
            else -> null
        }
        if (badge != null && cover != null) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    if (media is PostMedia.Video) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(12.dp))
                    } else {
                        Icon(badge.first, null, Modifier.size(11.dp))
                    }
                    badge.second?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 3.dp)) }
                }
            }
        }
    }
}

/**
 * A post as a tile in the gallery: its picture at its own shape, a play or count badge, and
 * the title over a soft shade at the bottom. Text posts get a tile of their own words.
 */
@Composable
fun GalleryTile(post: Post, actions: Actions, on: PostHandlers) {
    val showNsfw = LocalShowNsfw.current
    val cover = post.coverUrl()?.takeIf { (!post.over18 || showNsfw) && !post.spoiler }
    val shape = RoundedCornerShape(18.dp)
    if (post.coverUrl() == null) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().clip(shape).clickable { on.openThread(post) },
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    post.title.ifBlank { post.selftext },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                if (post.title.isNotBlank() && post.selftext.isNotBlank()) {
                    Text(
                        post.selftext,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                GalleryStats(post, actions, Modifier.padding(top = 10.dp), onPicture = false)
            }
        }
        return
    }
    val aspect = (post.coverAspect() ?: 1f).coerceIn(0.6f, 1.6f)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable {
                when (val m = post.media) {
                    is PostMedia.Image -> if (cover != null) on.openImage(m.picture.fullUrl, m.picture.url) else on.openThread(post)
                    else -> on.openThread(post)
                }
            },
    ) {
        if (cover != null) {
            AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Tag(if (post.over18) "NSFW" else "Spoiler", if (post.over18) NsfwColor else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when (val m = post.media) {
            is PostMedia.Video -> Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.PlayArrow, "Video", Modifier.padding(5.dp).size(16.dp))
            }
            is PostMedia.Gallery -> Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Icon(LurkIcons.Images, null, Modifier.size(12.dp))
                    Text("${m.pictures.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
                }
            }
            else -> Unit
        }
        if (post.title.isNotBlank()) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                    .clickable { on.openThread(post) }
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 10.dp),
            ) {
                Text(
                    post.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                GalleryStats(post, actions, Modifier.padding(top = 4.dp), onPicture = true)
            }
        }
    }
}

@Composable
private fun GalleryStats(post: Post, actions: Actions, modifier: Modifier, onPicture: Boolean) {
    if (!post.hasStats) return
    val color = if (onPicture) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(LurkIcons.Upvote, null, tint = color, modifier = Modifier.size(12.dp))
        Text(compact(actions.score(post.name, post.score, post.likes)), style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(start = 3.dp))
        Spacer(Modifier.width(10.dp))
        Icon(LurkIcons.Comment, null, tint = color, modifier = Modifier.size(11.dp))
        Text(compact(post.numComments), style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(start = 3.dp))
    }
}

internal val NsfwColor = Color(0xFFE5484D)

/** The icon for each layout, in menus and the layout sheet. */
fun PostLayout.icon(): ImageVector = when (this) {
    PostLayout.Compact -> LurkIcons.Compact
    PostLayout.Comfortable -> LurkIcons.Rows
    PostLayout.Large -> LurkIcons.Photo
    PostLayout.Gallery -> LurkIcons.Grid
}

/**
 * Picks how a feed looks: four tiles, each a small drawing of its layout. [feedName] is the
 * feed's name, for the heading; the choice is kept for that feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayoutSheet(current: PostLayout, feedName: String, onPick: (PostLayout) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text("Layout", style = MaterialTheme.typography.titleLarge)
            Text(
                "For $feedName. Set the default for every feed in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            LayoutChoices(current) { layout ->
                onPick(layout)
                scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }
}

/** The four layouts as a 2×2 grid of drawn previews. */
@Composable
fun LayoutChoices(current: PostLayout, onPick: (PostLayout) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PostLayout.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { layout ->
                    LayoutTile(layout, layout == current, Modifier.weight(1f)) { onPick(layout) }
                }
            }
        }
    }
}

@Composable
private fun LayoutTile(layout: PostLayout, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.clip(shape).clickable(onClickLabel = "Use ${layout.label}", onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(8.dp),
            ) {
                LayoutDrawing(layout)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Icon(layout.icon(), null, tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(
                    layout.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                if (selected) Icon(LurkIcons.Check, "Selected", tint = accent, modifier = Modifier.size(16.dp))
            }
            Text(
                layout.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A tiny picture of a layout, from blocks. */
@Composable
private fun LayoutDrawing(layout: PostLayout) {
    val line = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val media = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    val card = MaterialTheme.colorScheme.surfaceContainerLow
    @Composable
    fun Bar(width: Float, height: Int = 5) =
        Box(Modifier.fillMaxWidth(width).height(height.dp).clip(CircleShape).background(line))
    when (layout) {
        PostLayout.Compact -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(card).padding(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Bar(0.9f, 4)
                        Bar(0.5f, 3)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(media))
                }
            }
        }
        PostLayout.Comfortable -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(card).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Bar(0.8f)
                Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(5.dp)).background(media))
                Bar(0.4f, 4)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(card).padding(6.dp)) { Bar(0.7f) }
        }
        PostLayout.Large -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(card).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.padding(horizontal = 2.dp)) { Bar(0.75f, 6) }
            Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(5.dp)).background(media))
            Box(Modifier.padding(horizontal = 2.dp)) { Bar(0.35f, 4) }
        }
        PostLayout.Gallery -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(5.dp)).background(media))
                Box(Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(5.dp)).background(media))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(5.dp)).background(media))
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(5.dp)).background(media))
            }
        }
    }
}

/**
 * Where the feed budget ends a feed: a calm card that says so, with a way to carry on.
 * [read] posts so far this sitting.
 */
@Composable
fun BudgetCard(read: Int, budget: Int, onKeepGoing: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = PostCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(LurkIcons.Hourglass, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
                }
            }
            Text(
                "That's your $budget for now",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                "You've read $read posts this sitting. Take a break, and the count starts over after half an hour away.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            FilledTonalButton(onClick = onKeepGoing, modifier = Modifier.padding(top = 20.dp)) {
                Text("Keep going for $budget more")
            }
            TextButton(onClick = onSettings) { Text("Change the budget") }
        }
    }
}

/** A quiet line above a feed: how many posts muted words hid, and a way to see them. */
@Composable
fun MutedBanner(count: Int, showing: Boolean, onToggle: () -> Unit, onManage: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 14.dp, end = 4.dp),
    ) {
        Icon(LurkIcons.MuteWords, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            if (count == 1) "1 post hidden by muted words" else "$count posts hidden by muted words",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (showing) "Hide" else "Show") }
        TextButton(onClick = onManage, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Edit") }
    }
}

/** On a muted post that's being shown: which rule caught it. */
@Composable
fun MutedLabel(rule: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 6.dp),
    ) {
        Icon(LurkIcons.MuteWords, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        Text(
            "Muted for “$rule”",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * Over a joined community's feed once it's gone a month without a post: says so, and
 * points to Community health, which may know a busier copy.
 */
@Composable
fun QuietBanner(newestPost: Long, onHealth: () -> Unit) {
    val quietColor = Color(0xFFD98E04)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = quietColor.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onHealth),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)) {
            Icon(LurkIcons.Pulse, null, tint = quietColor, modifier = Modifier.size(18.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("This community has gone quiet", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (newestPost > 0) "Nothing new in ${ago(newestPost)}. See Community health for busier places."
                    else "No posts yet. See Community health for busier places.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(LurkIcons.Chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
