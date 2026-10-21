package app.lurk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lurk.data.FixedFeed
import app.lurk.data.Naming
import kotlinx.coroutines.launch

/**
 * Every community at once: search, Home and the fixed feeds (Popular / All), then
 * subscriptions A to Z. Typing a name you don't follow offers to go there anyway.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SubredditSheet(
    subreddits: List<String>,
    naming: Naming,
    fixedFeeds: List<FixedFeed>,
    /** Lowercase community name, the Home key, or null. */
    selected: String?,
    homeKey: String,
    signedIn: Boolean,
    syncing: Boolean,
    onHome: () -> Unit,
    onSubreddit: (String) -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    // Close, then navigate, so the sheet slides away instead of vanishing.
    fun pick(action: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); action() }
    }

    val typed = naming.clean(query)
    val matches = remember(subreddits, typed) {
        if (typed.isEmpty()) subreddits
        else subreddits.filter { it.contains(typed, ignoreCase = true) }
            .sortedBy { !it.startsWith(typed, ignoreCase = true) } // prefix matches first
    }
    val canGoTo = naming.isCommunityName(typed) && matches.none { it.equals(typed, ignoreCase = true) }
    // The keyboard's Go: an exact match, else the typed name, else the best match.
    fun go() {
        val target = matches.firstOrNull { it.equals(typed, ignoreCase = true) }
            ?: typed.takeIf { canGoTo }
            ?: matches.firstOrNull()
            ?: return
        pick { onSubreddit(target) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Find or go to a ${naming.communityNoun}") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            LazyColumn(
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            ) {
                if (typed.isEmpty()) {
                    item(key = "fixed") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            FeedTile("Home", selected == homeKey, Modifier.weight(1f), { pick(onHome) }) {
                                Icon(Icons.Default.Home, null, Modifier.size(22.dp))
                            }
                            for (feed in fixedFeeds) {
                                FeedTile(feed.label, selected == feed.community.lowercase(), Modifier.weight(1f), { pick { onSubreddit(feed.community) } }) {
                                    Avatar(feed.label.lowercase(), 22.dp)
                                }
                            }
                        }
                    }
                }
                if (canGoTo) {
                    item(key = "goto") {
                        SubredditRow("Go to ${naming.community(typed)}", typed, selected = false) { pick { onSubreddit(typed) } }
                    }
                }
                when {
                    !signedIn -> item(key = "signin") {
                        Hint("Sign in to see your ${naming.communityNoun.plural()}", onClick = { pick(onSignIn) })
                    }
                    subreddits.isEmpty() && syncing -> item(key = "loading") { Hint("Loading subscriptions…") }
                    typed.isNotEmpty() && matches.isEmpty() && !canGoTo -> item(key = "none") { Hint("No matches") }
                }
                if (typed.isEmpty()) {
                    // A to Z with a letter header per group; numbers and the like under "#".
                    matches.groupBy { it.first().uppercaseChar().takeIf(Char::isLetter) ?: '#' }
                        .toSortedMap(compareBy<Char> { if (it == '#') '{' else it })
                        .forEach { (letter, names) ->
                            stickyHeader(key = "h:$letter") { LetterHeader(letter) }
                            items(names, key = { "s:" + it.lowercase() }) { name ->
                                SubredditRow(name, name, selected == name.lowercase()) { pick { onSubreddit(name) } }
                            }
                        }
                } else {
                    items(matches, key = { "s:" + it.lowercase() }) { name ->
                        SubredditRow(name, name, selected == name.lowercase()) { pick { onSubreddit(name) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedTile(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 14.dp),
        ) {
            icon()
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LetterHeader(letter: Char) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Text(
            letter.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SubredditRow(label: String, avatar: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Avatar(avatar, 32.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Hint(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/** "subreddit" to "subreddits", "community" to "communities". */
fun String.plural(): String = if (endsWith("y")) dropLast(1) + "ies" else this + "s"
