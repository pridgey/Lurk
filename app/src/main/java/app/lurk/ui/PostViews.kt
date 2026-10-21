package app.lurk.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lurk.data.Actions
import app.lurk.data.Naming
import app.lurk.data.RedditNaming
import app.lurk.data.Picture
import app.lurk.data.Post
import app.lurk.data.PostMedia
import app.lurk.data.Vote
import coil.compose.AsyncImage

/** What a post (or anything in it) can ask the app to do. */
class PostHandlers(
    val openThread: (Post) -> Unit,
    val openLink: (String) -> Unit,
    val openImage: (String) -> Unit,
    val openCommunity: (String) -> Unit,
    val openUser: (String) -> Unit,
    /** Opens a thread by its ref, e.g. "continue this thread". */
    val openRef: (String) -> Unit,
    /** The site's own page for the post. */
    val openOnWeb: (Post) -> Unit,
    /** Plays the post's video full screen from a spot (ms), or sends it to the site it's from. */
    val openVideo: (Post, Long) -> Unit,
    val share: (Post) -> Unit,
    /** The video playing in place in the list, if any. */
    val inlineVideo: InlineVideo = InlineVideo(),
)

/** Whether the active account shows NSFW media without a tap. */
val LocalShowNsfw = compositionLocalOf { false }

/** How the active account's site names communities and people. */
val LocalNaming = compositionLocalOf<Naming> { RedditNaming }

private val CardShape = RoundedCornerShape(24.dp)
private val MediaShape = RoundedCornerShape(16.dp)

/** A post in a feed: a card; tapping it opens the thread. */
@Composable
fun PostCard(post: Post, showCommunity: Boolean, actions: Actions, on: PostHandlers) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(CardShape)
            .clickable { on.openThread(post) },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            PostHeader(post, showCommunity, on)
            if (post.title.isNotBlank()) {
                Text(
                    post.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            PostTags(post)
            if (post.isSelf && post.selftext.isNotBlank()) {
                // Without a title, the text is the post: show more of it, in full color.
                val untitled = post.title.isBlank()
                Text(
                    post.selftext,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (untitled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (untitled) 8 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!post.isSelf && post.media == null) LinkPreview(post, on)
            post.media?.let { PostMediaView(post, it, compact = true, on = on) }
            PostActions(post, actions, on, onComments = { on.openThread(post) })
        }
    }
}

/** The post at the top of its thread: full text, every image. */
@Composable
fun PostDetail(post: Post, actions: Actions, on: PostHandlers) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        PostHeader(post, showCommunity = true, on = on)
        if (post.title.isNotBlank()) {
            Text(
                post.title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        PostTags(post)
        if (!post.isSelf && post.media == null) LinkPreview(post, on)
        post.media?.let { PostMediaView(post, it, compact = false, on = on) }
        post.selftextHtml?.let { html ->
            CommentBody(
                html,
                post.bodyImages,
                onLink = on.openLink,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        PostActions(post, actions, on, onComments = null)
    }
}

/** A colored circle with a name's first letter. */
@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: nameColor(name)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trimStart('_').take(1).uppercase(),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45f).sp,
        )
    }
}

@Composable
private fun PostHeader(post: Post, showCommunity: Boolean, on: PostHandlers) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val naming = LocalNaming.current
    // Sites without communities always lead with the author.
    val community = post.community?.takeIf { showCommunity }
    val open = { if (community != null) on.openCommunity(community) else on.openUser(post.author) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(community ?: post.author, 34.dp, Modifier.clickable(onClick = open))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(
                if (community != null) naming.community(community) else naming.user(post.author),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = open),
            )
            Text(
                (if (community != null) naming.user(post.author) + " · " else "") + ago(post.createdUtc),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostTags(post: Post) {
    val tags = buildList {
        if (post.stickied) add("Pinned" to MaterialTheme.colorScheme.tertiary)
        if (post.over18) add("NSFW" to Color(0xFFE5484D))
        if (post.spoiler) add("Spoiler" to MaterialTheme.colorScheme.onSurfaceVariant)
        if (post.locked) add("Locked" to Color(0xFFE5A50A))
        post.flair?.let { add(it to MaterialTheme.colorScheme.secondary) }
    }
    if (tags.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        tags.forEach { (label, color) -> Tag(label, color) }
    }
}

@Composable
fun Tag(label: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.13f), contentColor = color) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** A link post's target: thumbnail, domain, and an arrow, as one tappable tile. */
@Composable
private fun LinkPreview(post: Post, on: PostHandlers) {
    Surface(
        shape = MediaShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(MediaShape)
            .clickable { on.openLink(post.url) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            val thumb = post.thumbnail?.takeIf { (!post.over18 || LocalShowNsfw.current) && !post.spoiler }
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    LurkIcons.External, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                if (thumb != null) {
                    AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                Text(
                    post.domain.removePrefix("www."),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    post.url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                LurkIcons.External, "Open link",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 6.dp).size(18.dp),
            )
        }
    }
}

@Composable
private fun PostMediaView(post: Post, media: PostMedia, compact: Boolean, on: PostHandlers) {
    val showNsfw = LocalShowNsfw.current
    var revealed by remember(post.name, showNsfw) { mutableStateOf(!((post.over18 && !showNsfw) || post.spoiler)) }
    val modifier = Modifier.padding(top = 10.dp)
    if (!revealed) {
        Box(
            modifier
                .fillMaxWidth()
                .aspectRatio(2f)
                .clip(MediaShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { revealed = true },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Tag(if (post.over18) "NSFW" else "Spoiler", if (post.over18) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Tap to show",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        return
    }
    when (media) {
        is PostMedia.Image -> PictureView(media.picture, modifier) { on.openImage(media.picture.fullUrl) }
        is PostMedia.Video -> Box(modifier, contentAlignment = Alignment.Center) {
            val preview = media.preview
            val stream = media.stream
            // Reddit-hosted videos play right here; anything else opens its own player.
            val playHere = { if (stream != null) on.inlineVideo.playing = post.name else on.openVideo(post, 0) }
            if (stream != null && on.inlineVideo.playing == post.name) {
                // Same box the preview filled, so the list doesn't jump.
                val aspect = preview?.aspect ?: if (stream.width > 0 && stream.height > 0) stream.width.toFloat() / stream.height else 16f / 9f
                InlineVideoPlayer(
                    stream,
                    Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.8f, 2.4f)).clip(MediaShape),
                    onFullscreen = { position ->
                        on.inlineVideo.playing = null
                        on.openVideo(post, position)
                    },
                )
                // Scrolled away or left the screen: stop for good rather than restart on return.
                DisposableEffect(post.name) {
                    onDispose { if (on.inlineVideo.playing == post.name) on.inlineVideo.playing = null }
                }
                return@Box
            }
            if (preview != null) {
                PictureView(preview, Modifier, onClick = playHere)
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(MediaShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable(onClick = playHere),
                )
            }
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White) {
                Icon(Icons.Default.PlayArrow, "Play video", Modifier.padding(12.dp).size(30.dp))
            }
            val badge = when {
                stream == null -> media.embed?.provider ?: post.domain.removePrefix("www.")
                stream.isGif -> "GIF"
                stream.durationSec > 0 -> duration(stream.durationSec)
                else -> null
            }
            if (badge != null) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        // ↗ marks videos that can only play on their own site.
                        if (stream == null && media.embed == null) Icon(LurkIcons.External, null, Modifier.padding(end = 5.dp).size(13.dp))
                        Text(badge, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        // 🔊 marks GIFs that have sound.
                        if (stream?.isGif == true && stream.hasAudio == true) Icon(LurkIcons.Volume, "Has sound", Modifier.padding(start = 5.dp).size(13.dp))
                    }
                }
            }
        }
        is PostMedia.Gallery -> if (compact) {
            Box(modifier) {
                PictureView(media.pictures.first(), Modifier) { on.openThread(post) }
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(LurkIcons.Images, null, Modifier.size(14.dp))
                        Text(
                            "${media.pictures.size}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
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

@Composable
private fun PictureView(picture: Picture, modifier: Modifier, onClick: () -> Unit) {
    AsyncImage(
        model = picture.url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            // Very tall images are cropped in lists; the viewer shows them whole.
            .aspectRatio(picture.aspect.coerceIn(0.8f, 2.4f))
            .clip(MediaShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun PostActions(post: Post, actions: Actions, on: PostHandlers, onComments: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
    ) {
        VotePill(post.name, post.score, post.likes, actions)
        Pill(onClick = onComments) {
            Icon(LurkIcons.Comment, null, Modifier.size(18.dp))
            Text(
                compact(post.numComments),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        val saved = actions.saved(post.name, post.saved)
        RoundIcon(
            if (saved) LurkIcons.BookmarkFilled else LurkIcons.Bookmark,
            if (saved) "Unsave" else "Save",
            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) { actions.toggleSave(post.name, post.saved) }
        RoundIcon(LurkIcons.Share, "Share", MaterialTheme.colorScheme.onSurfaceVariant) { on.share(post) }
    }
}

/** A rounded, tinted container for actions. */
@Composable
private fun Pill(
    onClick: (() -> Unit)?,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.height(36.dp).clip(CircleShape).then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { content() }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

private fun voteColor(vote: Vote, neutral: Color): Color = when (vote) {
    Vote.Up -> UpvoteColor
    Vote.Down -> DownvoteColor
    Vote.None -> neutral
}

/** Up, score, down in one pill, tinted when voted. */
@Composable
private fun VotePill(name: String, score: Int, likes: Boolean?, actions: Actions) {
    val vote = actions.vote(name, likes)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val color by animateColorAsState(voteColor(vote, neutral), label = "vote")
    val background by animateColorAsState(
        if (vote == Vote.None) MaterialTheme.colorScheme.surfaceContainerHigh else voteColor(vote, neutral).copy(alpha = 0.14f),
        label = "voteBg",
    )
    Surface(shape = CircleShape, color = background, modifier = Modifier.height(36.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VoteButton(up = true, vote, color, neutral) { actions.toggleVote(name, likes, up = true) }
            Text(
                compact(actions.score(name, score, likes)),
                style = MaterialTheme.typography.labelLarge,
                color = if (vote == Vote.None) MaterialTheme.colorScheme.onSurface else color,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 28.dp),
            )
            if (actions.downvotes || vote == Vote.Down) {
                VoteButton(up = false, vote, color, neutral) { actions.toggleVote(name, likes, up = false) }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

@Composable
private fun VoteButton(up: Boolean, vote: Vote, activeColor: Color, neutral: Color, onClick: () -> Unit) {
    val active = if (up) vote == Vote.Up else vote == Vote.Down
    val icon = when {
        up && active -> LurkIcons.UpvoteFilled
        up -> LurkIcons.Upvote
        active -> LurkIcons.DownvoteFilled
        else -> LurkIcons.Downvote
    }
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            if (up) "Upvote" else "Downvote",
            tint = if (active) activeColor else neutral,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** Compact up / score / down for comments, without a background. */
@Composable
fun VoteControls(name: String, score: Int, likes: Boolean?, actions: Actions, scoreHidden: Boolean) {
    val vote = actions.vote(name, likes)
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val color by animateColorAsState(voteColor(vote, neutral), label = "vote")
    Row(verticalAlignment = Alignment.CenterVertically) {
        VoteButton(up = true, vote, color, neutral) { actions.toggleVote(name, likes, up = true) }
        Text(
            if (scoreHidden) "–" else compact(actions.score(name, score, likes)),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.widthIn(min = 24.dp),
        )
        if (actions.downvotes || vote == Vote.Down) {
            VoteButton(up = false, vote, color, neutral) { actions.toggleVote(name, likes, up = false) }
        }
    }
}

/** A grey placeholder card that pulses while the feed loads. */
@Composable
fun SkeletonCard() {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val block = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = pulse)
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(block))
                Column(Modifier.padding(start = 10.dp)) {
                    Box(Modifier.width(110.dp).height(11.dp).clip(CircleShape).background(block))
                    Box(Modifier.padding(top = 6.dp).width(70.dp).height(9.dp).clip(CircleShape).background(block))
                }
            }
            Box(Modifier.padding(top = 14.dp).fillMaxWidth(0.92f).height(15.dp).clip(CircleShape).background(block))
            Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.6f).height(15.dp).clip(CircleShape).background(block))
            Box(Modifier.padding(top = 14.dp).fillMaxWidth().aspectRatio(1.9f).clip(MediaShape).background(block))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(110.dp).height(36.dp).clip(CircleShape).background(block))
                Box(Modifier.width(64.dp).height(36.dp).clip(CircleShape).background(block))
            }
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
            textAlign = TextAlign.Center,
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
