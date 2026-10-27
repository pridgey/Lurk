package app.lurk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lurk.data.CommunityInfo
import app.lurk.data.DiscoverScreen
import app.lurk.data.Peek
import app.lurk.data.Post
import app.lurk.data.PostMedia
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Where a drag (or a button press) counts as a swipe, as a share of the card's width. */
private const val SWIPE_AT = 0.28f
private const val FLING_VELOCITY = 1_800f
private val JoinColor = Color(0xFF2EB67D)
private val SkipColor = Color(0xFFE5484D)

/** The top card's position while it's dragged or thrown. */
private class SwipeState(startX: Float) {
    val x = Animatable(startX)
    val y = Animatable(0f)
    /** Past the swipe point, for a tick of haptics on the way across. */
    var over = false
    /** Being thrown off; ignore further swipes. */
    var leaving = false
}

/**
 * Discover: a deck of community cards. Drag right (or tap +) to join, left (or ✕) to
 * skip, tap a card to look inside. Undo brings the last card back.
 */
@Composable
fun DiscoverView(
    screen: DiscoverScreen,
    signedIn: Boolean,
    /** "online" (Reddit) or "active this week" (Lemmy). */
    activeLabel: String,
    onOpen: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    LaunchedEffect(screen) { screen.start() }
    // Back leaves the search before it leaves Discover.
    BackHandler(enabled = screen.isSearching) { screen.clearSearch() }
    val naming = LocalNaming.current
    val noun = naming.communityNoun
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val top = screen.deck.firstOrNull()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val threshold = widthPx * SWIPE_AT

        // Undo throws the card back in from the side it left by.
        val comingBack = remember { floatArrayOf(0f) }
        val state = remember(top?.name) { SwipeState(comingBack[0]).also { comingBack[0] = 0f } }
        LaunchedEffect(state) {
            if (state.x.value != 0f) state.x.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
        }

        fun settle() {
            scope.launch { state.x.animateTo(0f, spring(dampingRatio = 0.6f)) }
            scope.launch { state.y.animateTo(0f, spring(dampingRatio = 0.6f)) }
            state.over = false
        }

        fun commit(join: Boolean) {
            if (top == null || state.leaving) return
            if (join && !signedIn) {
                settle()
                onMessage("Sign in to join ${noun.plural()}")
                return
            }
            state.leaving = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                launch { state.y.animateTo(state.y.value + widthPx * 0.15f, tween(220)) }
                state.x.animateTo(if (join) widthPx * 1.5f else -widthPx * 1.5f, tween(220))
                screen.swipe(join)
            }
        }

        fun undo() {
            val last = screen.last ?: return
            comingBack[0] = if (last.joined) widthPx * 1.5f else -widthPx * 1.5f
            screen.undo()
        }

        Column(Modifier.fillMaxSize()) {
            SearchField(screen.query, "Search ${noun.plural()} by topic", screen::type, screen::submitSearch, screen::clearSearch)
            if (screen.isSearching) {
                SearchResults(screen, signedIn, activeLabel, onOpen, onMessage, Modifier.weight(1f))
            } else {
                Text(
                    when {
                        screen.joined + screen.skipped > 0 -> "${screen.joined} joined · ${screen.skipped} skipped"
                        signedIn -> "Swipe right to join, left to skip. Tap a card to look inside."
                        else -> "Swipe left to skip, tap a card to look inside. Sign in to join."
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                )

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .pointerInput(top?.name, signedIn) {
                            if (top == null) return@pointerInput
                            val tracker = VelocityTracker()
                            detectDragGestures(
                                onDragStart = { tracker.resetTracking() },
                                onDragEnd = {
                                    val vx = tracker.calculateVelocity().x
                                    val x = state.x.value
                                    when {
                                        x > threshold || (vx > FLING_VELOCITY && x > 0) -> commit(true)
                                        x < -threshold || (vx < -FLING_VELOCITY && x < 0) -> commit(false)
                                        else -> settle()
                                    }
                                },
                                onDragCancel = { settle() },
                            ) { change, drag ->
                                if (state.leaving) return@detectDragGestures
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val nextX = state.x.value + drag.x
                                val over = abs(nextX) > threshold
                                if (over && !state.over) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                state.over = over
                                scope.launch {
                                    state.x.snapTo(state.x.value + drag.x)
                                    state.y.snapTo(state.y.value + drag.y)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val error = screen.error
                    when {
                        top != null -> {
                            val progress = (abs(state.x.value) / threshold).coerceIn(0f, 1f)
                            // Back to front, so the top card draws last.
                            val shown = screen.deck.take(3)
                            for (i in shown.indices.reversed()) {
                                val community = shown[i]
                                key(community.name) {
                                    val depth = if (i == 0) 0f else i - progress
                                    CommunityCard(
                                        info = community,
                                        peek = screen.peeks[community.name],
                                    peekLabel = screen.peekLabel,
                                        activeLabel = activeLabel,
                                        joinStamp = if (i == 0) (state.x.value / threshold).coerceIn(0f, 1f) else 0f,
                                        skipStamp = if (i == 0) (-state.x.value / threshold).coerceIn(0f, 1f) else 0f,
                                        onClick = { onOpen(community.name) },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(bottom = 20.dp)
                                            .graphicsLayer {
                                                if (i == 0) {
                                                    translationX = state.x.value
                                                    translationY = state.y.value
                                                    rotationZ = state.x.value / widthPx * 14f
                                                } else {
                                                    val scale = 1f - 0.05f * depth
                                                    scaleX = scale
                                                    scaleY = scale
                                                    translationY = 12.dp.toPx() * depth * 2
                                                    alpha = if (i == 2) progress.coerceAtLeast(0.6f) else 1f
                                                }
                                            },
                                    )
                                }
                            }
                        }
                        error != null -> Notice(error, "Try again", { screen.refresh() })
                        screen.loading || !screen.started -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "Shuffling the deck…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                        }
                        screen.exhausted -> Notice(
                            "That's every ${noun} we could find. Start over to see the ones you skipped again.",
                            "Start over",
                            { screen.startOver() },
                        )
                        else -> Notice("Nothing new on this stretch.", "Keep looking", { screen.dealMore() })
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp),
                ) {
                    RoundButton(LurkIcons.Undo, "Undo", 52.dp, enabled = screen.last != null) { undo() }
                    RoundButton(Icons.Default.Close, "Skip", 68.dp, enabled = top != null, tint = SkipColor) { commit(false) }
                    RoundButton(LurkIcons.Eye, "Look inside", 52.dp, enabled = top != null) { top?.let { onOpen(it.name) } }
                    if (signedIn) {
                        RoundButton(Icons.Default.Add, "Join", 68.dp, enabled = top != null, filled = true) { commit(true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    placeholder: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "Clear search") }
            }
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit() }),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/** Communities matching the search, biggest first; loads more as you scroll. */
@Composable
private fun SearchResults(
    screen: DiscoverScreen,
    signedIn: Boolean,
    activeLabel: String,
    onOpen: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier,
) {
    val noun = LocalNaming.current.communityNoun
    val list = rememberLazyListState()
    // A new query starts back at the top.
    LaunchedEffect(screen.searched) { list.scrollToItem(0) }
    val error = screen.searchError
    Box(modifier.fillMaxWidth()) {
        when {
            screen.searched == null && screen.query.trim().length < 2 ->
                Notice("Type a topic, like \"gardening\" or \"retro games\".")
            screen.searched == null || (screen.results.isEmpty() && screen.searching) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            screen.results.isEmpty() && error != null -> Notice(error, "Try again", screen::retrySearch)
            screen.results.isEmpty() -> Notice("No ${noun.plural()} match \"${screen.searched}\".")
            else -> LazyColumn(
                state = list,
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                items(screen.results, key = { "r:" + it.name.lowercase() }) { info ->
                    ResultRow(
                        info = info,
                        activeLabel = activeLabel,
                        joined = info.subscribed || screen.isSubscribed(info.name),
                        signedIn = signedIn,
                        onOpen = { onOpen(info.name) },
                        onJoin = {
                            if (signedIn) screen.toggleJoin(info) else onMessage("Sign in to join ${noun.plural()}")
                        },
                    )
                }
                item(key = "end") {
                    when {
                        error != null -> Notice(error, "Try again", screen::retrySearch, Modifier.fillMaxWidth())
                        !screen.searchEnded -> {
                            LaunchedEffect(screen.results.size) { screen.searchMore() }
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
        // A thin bar while a new query replaces the results on screen.
        if (screen.searching && screen.results.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 32.dp))
        }
    }
}

@Composable
private fun ResultRow(
    info: CommunityInfo,
    activeLabel: String,
    joined: Boolean,
    signedIn: Boolean,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
) {
    val naming = LocalNaming.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open ${naming.community(info.name)}", onClick = onOpen)
            .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        CommunityIcon(info, 52.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    naming.community(info.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (info.over18) {
                    Spacer(Modifier.width(6.dp))
                    Tag("NSFW", SkipColor)
                }
            }
            val stats = buildList {
                info.subscribers?.let { add("${compact(it)} ${naming.memberNoun}") }
                info.activeUsers?.takeIf { it > 0 }?.let { add("${compact(it)} $activeLabel") }
            }
            if (stats.isNotEmpty()) {
                Text(stats.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            val blurb = info.description.ifBlank { info.title.orEmpty() }
            if (blurb.isNotBlank()) {
                Text(blurb, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (signedIn) {
            if (joined) {
                OutlinedButton(onClick = onJoin, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Joined") }
            } else {
                FilledTonalButton(onClick = onJoin, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Join") }
            }
        }
    }
}

@Composable
private fun CommunityCard(
    info: CommunityInfo,
    peek: Peek?,
    peekLabel: String,
    activeLabel: String,
    joinStamp: Float,
    skipStamp: Float,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val naming = LocalNaming.current
    val showNsfw = LocalShowNsfw.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(28.dp)
    // No banner: the first picture from its top posts, else its own colors.
    val hero = info.banner ?: (peek as? Peek.Loaded)?.posts?.firstNotNullOfOrNull { it.peekImage(showNsfw) }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 6.dp,
        modifier = modifier.clip(shape).clickable(onClickLabel = "Open ${naming.community(info.name)}", onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(HERO_HEIGHT)
                    .background(Brush.linearGradient(listOf(nameColor(info.name), nameColor(info.name + "~")))),
            ) {
                if (hero != null) {
                    AsyncImage(
                        model = hero,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Column(Modifier.fillMaxSize().padding(top = HERO_HEIGHT - ICON_SIZE / 2)) {
                CommunityIcon(info, ICON_SIZE, Modifier.padding(start = 16.dp))
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            naming.community(info.name),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (info.over18) {
                            Spacer(Modifier.width(8.dp))
                            Tag("NSFW", SkipColor)
                        }
                    }
                    info.title?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val stats = buildList {
                        info.subscribers?.let { add("${compact(it)} ${naming.memberNoun}") }
                        info.activeUsers?.takeIf { it > 0 }?.let { add("${compact(it)} $activeLabel") }
                    }
                    if (stats.isNotEmpty()) {
                        Text(
                            stats.joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (info.description.isNotBlank()) {
                        Text(
                            info.description,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    Text(
                        peekLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = muted,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    when (peek) {
                        null, Peek.Loading -> repeat(3) { PeekSkeleton() }
                        Peek.Failed -> Text("Couldn't load its posts.", style = MaterialTheme.typography.bodyMedium, color = muted)
                        is Peek.Loaded ->
                            if (peek.posts.isEmpty()) Text("Quiet lately.", style = MaterialTheme.typography.bodyMedium, color = muted)
                            else peek.posts.forEach { PeekRow(it, showNsfw) }
                    }
                }
            }

            Stamp("JOIN", JoinColor, -14f, joinStamp, Modifier.align(Alignment.TopStart).padding(20.dp))
            Stamp("SKIP", SkipColor, 14f, skipStamp, Modifier.align(Alignment.TopEnd).padding(20.dp))
        }
    }
}

private val HERO_HEIGHT = 150.dp
private val ICON_SIZE = 76.dp

/** The community's own icon, on a ring of the card's color; its letter avatar if it has none. */
@Composable
private fun CommunityIcon(info: CommunityInfo, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            Avatar(LocalNaming.current.label(info.name), size - 8.dp)
            if (info.icon != null) {
                AsyncImage(
                    model = info.icon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            }
        }
    }
}

@Composable
private fun PeekRow(post: Post, showNsfw: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
        val image = post.peekImage(showNsfw)
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    LurkIcons.Comment,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                post.title.ifBlank { post.selftext },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${compact(post.score)} points · ${compact(post.numComments)} comments",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeekSkeleton() {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(block))
        Column(Modifier.padding(start = 12.dp)) {
            Box(Modifier.fillMaxWidth(0.85f).height(14.dp).clip(CircleShape).background(block))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.4f).height(10.dp).clip(CircleShape).background(block))
        }
    }
}

/** "JOIN" / "SKIP", inked onto the card as it's dragged. */
@Composable
private fun Stamp(label: String, color: Color, angle: Float, amount: Float, modifier: Modifier) {
    if (amount <= 0f) return
    Text(
        label,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
        color = color,
        modifier = modifier
            .graphicsLayer {
                alpha = amount
                val s = 0.8f + 0.2f * amount
                scaleX = s
                scaleY = s
            }
            .rotate(angle)
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
            .border(BorderStroke(3.dp, color), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: Dp,
    enabled: Boolean,
    tint: Color? = null,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val container = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow
    val content = when {
        filled -> MaterialTheme.colorScheme.onPrimary
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        shadowElevation = if (enabled) 3.dp else 0.dp,
        modifier = Modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.42f))
        }
    }
}

/** A post's picture for a small thumbnail, unless it should stay hidden. */
private fun Post.peekImage(showNsfw: Boolean): String? {
    if ((over18 && !showNsfw) || spoiler) return null
    return when (val m = media) {
        is PostMedia.Image -> m.picture.url
        is PostMedia.Gallery -> m.pictures.firstOrNull()?.url
        is PostMedia.Video -> m.preview?.url
        null -> thumbnail
    }
}
