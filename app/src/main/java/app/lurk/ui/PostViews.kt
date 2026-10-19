package app.lurk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lurk.data.Actions
import app.lurk.data.Picture
import app.lurk.data.Post
import app.lurk.data.PostMedia
import app.lurk.data.Vote
import coil.compose.AsyncImage

val UpvoteColor = Color(0xFFFF4500)
val DownvoteColor = Color(0xFF7193FF)

/** What a post (or anything in it) can ask the app to do. */
class PostHandlers(
    val openThread: (Post) -> Unit,
    val openLink: (String) -> Unit,
    val openImage: (String) -> Unit,
    val openSubreddit: (String) -> Unit,
    val openUser: (String) -> Unit,
    /** Old Reddit's page for the post, for video playback. */
    val openOnWeb: (Post) -> Unit,
)

/** A post in a feed: compact, tapping it opens the thread. */
@Composable
fun PostCard(post: Post, showSubreddit: Boolean, actions: Actions, on: PostHandlers) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { on.openThread(post) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
    ) {
        PostMeta(post, showSubreddit, on)
        Row(Modifier.padding(top = 4.dp)) {
            Text(
                post.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            post.thumbnail?.let { thumb ->
                if (!post.over18 && !post.spoiler) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = "Link preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { on.openLink(post.url) },
                    )
                }
            }
        }
        PostTags(post)
        if (!post.isSelf && post.media == null) LinkLine(post, on)
        if (post.isSelf && post.selftext.isNotBlank()) {
            Text(
                post.selftext,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        post.media?.let { PostMediaView(post, it, compact = true, on = on) }
        PostActions(post, actions, onComments = { on.openThread(post) })
    }
}

/** The post at the top of its thread: full text, all images. */
@Composable
fun PostDetail(post: Post, actions: Actions, on: PostHandlers) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        PostMeta(post, showSubreddit = true, on = on)
        Text(
            post.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
        PostTags(post)
        if (!post.isSelf && post.media == null) LinkLine(post, on)
        post.media?.let { PostMediaView(post, it, compact = false, on = on) }
        post.selftextHtml?.let { html ->
            HtmlText(
                html,
                onLink = on.openLink,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        PostActions(post, actions, onComments = null)
    }
}

@Composable
private fun PostMeta(post: Post, showSubreddit: Boolean, on: PostHandlers) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showSubreddit) {
            Text(
                "r/${post.subreddit}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { on.openSubreddit(post.subreddit) },
            )
            Text(" · ", style = MaterialTheme.typography.labelMedium, color = muted)
        }
        Text(
            "u/${post.author}",
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).clickable { on.openUser(post.author) },
        )
        Text(" · ${ago(post.createdUtc)}", style = MaterialTheme.typography.labelMedium, color = muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostTags(post: Post) {
    val tags = buildList {
        if (post.stickied) add("Pinned" to MaterialTheme.colorScheme.tertiary)
        if (post.over18) add("NSFW" to Color(0xFFD32F2F))
        if (post.spoiler) add("Spoiler" to MaterialTheme.colorScheme.onSurfaceVariant)
        if (post.locked) add("Locked" to MaterialTheme.colorScheme.onSurfaceVariant)
        post.flair?.let { add(it to MaterialTheme.colorScheme.secondary) }
    }
    if (tags.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        tags.forEach { (label, color) ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = color.copy(alpha = 0.14f),
                contentColor = color,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun LinkLine(post: Post, on: PostHandlers) {
    Text(
        "${post.domain} ↗",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(top = 6.dp)
            .clickable { on.openLink(post.url) },
    )
}

@Composable
private fun PostMediaView(post: Post, media: PostMedia, compact: Boolean, on: PostHandlers) {
    var revealed by remember(post.name) { mutableStateOf(!(post.over18 || post.spoiler)) }
    val modifier = Modifier.padding(top = 8.dp)
    if (!revealed) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier.fillMaxWidth().aspectRatio(2f).clickable { revealed = true },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    (if (post.over18) "NSFW" else "Spoiler") + " · tap to show",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        return
    }
    when (media) {
        is PostMedia.Image -> PictureView(media.picture, modifier) { on.openImage(media.picture.fullUrl) }
        is PostMedia.Video -> {
            val preview = media.preview
            Box(modifier, contentAlignment = Alignment.Center) {
                if (preview != null) {
                    PictureView(preview, Modifier) { on.openOnWeb(post) }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { on.openOnWeb(post) },
                    ) {}
                }
                Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
                    Icon(Icons.Default.PlayArrow, "Play video", Modifier.padding(10.dp).size(32.dp))
                }
            }
        }
        is PostMedia.Gallery -> {
            if (compact) {
                Box(modifier) {
                    PictureView(media.pictures.first(), Modifier) { on.openThread(post) }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    ) {
                        Text(
                            "1 / ${media.pictures.size}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            } else {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    media.pictures.forEach { picture ->
                        PictureView(picture, Modifier) { on.openImage(picture.fullUrl) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PictureView(picture: Picture, modifier: Modifier, onClick: () -> Unit) {
    AsyncImage(
        model = picture.url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            // Very tall images are cropped in lists; the viewer shows them whole.
            .aspectRatio(picture.aspect.coerceIn(0.8f, 3f))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun PostActions(post: Post, actions: Actions, onComments: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        VoteControls(post.name, post.score, post.likes, actions, scoreHidden = false)
        Spacer(Modifier.weight(1f))
        if (onComments != null) {
            TextButton(onClick = onComments) {
                Text("${compact(post.numComments)} comments")
            }
        } else {
            Text(
                "${compact(post.numComments)} comments",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val saved = actions.saved(post.name, post.saved)
        IconButton(onClick = { actions.toggleSave(post.name, post.saved) }) {
            Icon(
                Icons.Default.Star,
                contentDescription = if (saved) "Unsave" else "Save",
                tint = if (saved) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

/** Up arrow, score, down arrow. */
@Composable
fun VoteControls(
    name: String,
    score: Int,
    likes: Boolean?,
    actions: Actions,
    scoreHidden: Boolean,
    small: Boolean = false,
) {
    val vote = actions.vote(name, likes)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val iconSize = if (small) 20.dp else 24.dp
    val buttonModifier = if (small) Modifier.size(32.dp) else Modifier
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { actions.toggleVote(name, likes, up = true) }, modifier = buttonModifier) {
            Icon(
                Icons.Default.KeyboardArrowUp, "Upvote",
                tint = if (vote == Vote.Up) UpvoteColor else muted,
                modifier = Modifier.size(iconSize),
            )
        }
        Text(
            if (scoreHidden) "–" else compact(actions.score(name, score, likes)),
            style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = when (vote) { Vote.Up -> UpvoteColor; Vote.Down -> DownvoteColor; Vote.None -> muted },
            modifier = Modifier.width(if (small) 32.dp else 40.dp),
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = { actions.toggleVote(name, likes, up = false) }, modifier = buttonModifier) {
            Icon(
                Icons.Default.KeyboardArrowDown, "Downvote",
                tint = if (vote == Vote.Down) DownvoteColor else muted,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Centered message with an optional button, for empty and error states. */
@Composable
fun Notice(text: String, action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier.fillMaxSize()) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
