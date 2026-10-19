package app.lurk.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.lurk.data.Account
import app.lurk.data.AccountSession
import app.lurk.data.AccountStore
import app.lurk.web.Links
import app.lurk.web.RedditEngine
import app.lurk.web.Scripts
import app.lurk.web.detached
import kotlinx.coroutines.launch

@Composable
fun LurkApp(store: AccountStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scripts = remember { Scripts(context) }
    val onMessage: (String) -> Unit = remember {
        { message -> scope.launch { snackbar.showSnackbar(message) } }
    }
    val onExternal: (Uri) -> Unit = remember { { uri -> openExternal(context, uri) } }

    // Each account gets its own session (browser + engine). Switching accounts tears
    // the old one down and builds a new one on that account's WebView profile.
    val activeId = store.activeId
    key(activeId) {
        val session = remember {
            AccountSession(context, store, activeId, scripts, onMessage, onExternal)
        }
        DisposableEffect(session) {
            session.start(signInFirst = store.consumePendingLogin(activeId))
            onDispose { session.destroy() }
        }
        AccountScreen(store, session, snackbar)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AccountScreen(store: AccountStore, session: AccountSession, snackbar: SnackbarHostState) {
    val browser = session.browser
    val account = session.account ?: return
    val location = remember(browser.url) { Links.locate(browser.url) }
    var debugOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val context = LocalContext.current

    BackHandler(enabled = debugOpen || browser.canGoBack) {
        if (debugOpen) debugOpen = false else browser.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(location.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            account.username?.let { "u/$it" } ?: "Not signed in",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    AccountMenu(
                        store = store,
                        current = account,
                        onSignIn = { browser.open(Links.LOGIN) },
                        onAdd = {
                            if (account.username == null) {
                                browser.open(Links.LOGIN) // reuse this empty slot
                            } else {
                                val added = store.add()
                                store.requestLogin(added.id)
                                store.setActive(added.id)
                            }
                        },
                        onSignOut = { confirmSignOut = true },
                    )
                    OverflowMenu(
                        syncing = session.syncing,
                        onRefresh = { browser.reload() },
                        onSync = { session.requestSync() },
                        onInbox = { browser.open(Links.INBOX) },
                        onOpenOutside = { browser.url?.let { openExternal(context, Uri.parse(it)) } },
                        onDebug = { debugOpen = true },
                    )
                },
            )
        },
        bottomBar = {
            SubredditBar(
                account = account,
                location = location,
                syncing = session.syncing,
                onOpen = { browser.open(it) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            AndroidView(
                factory = { browser.webView.detached() },
                modifier = Modifier.fillMaxSize(),
            )
            if (browser.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { browser.progress / 100f },
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                )
            }
            EngineHost(
                engine = session.engine,
                open = debugOpen,
                syncing = session.syncing,
                onSync = { session.requestSync() },
                onClose = { debugOpen = false },
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of u/${account.username}?") },
            text = { Text("This removes the account from Lurk on this phone. You can add it again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    session.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AccountMenu(
    store: AccountStore,
    current: Account,
    onSignIn: () -> Unit,
    onAdd: () -> Unit,
    onSignOut: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.AccountCircle, contentDescription = "Accounts")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            store.accounts.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.username?.let { "u/$it" } ?: "Not signed in") },
                    leadingIcon = {
                        if (a.id == current.id) Icon(Icons.Default.Check, contentDescription = "Active")
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        open = false
                        store.setActive(a.id)
                    },
                )
            }
            HorizontalDivider()
            if (current.username == null) {
                DropdownMenuItem(text = { Text("Sign in") }, onClick = { open = false; onSignIn() })
            }
            if (store.multiProfile) {
                DropdownMenuItem(
                    text = { Text("Add account") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                    onClick = { open = false; onAdd() },
                )
            }
            if (current.username != null) {
                DropdownMenuItem(
                    text = { Text("Sign out of u/${current.username}") },
                    onClick = { open = false; onSignOut() },
                )
            }
            if (!store.multiProfile) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Multiple accounts need a newer Android System WebView",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    syncing: Boolean,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onInbox: () -> Unit,
    onOpenOutside: () -> Unit,
    onDebug: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Refresh page") }, onClick = { open = false; onRefresh() })
            DropdownMenuItem(
                text = { Text(if (syncing) "Syncing subscriptions…" else "Sync subscriptions") },
                enabled = !syncing,
                onClick = { open = false; onSync() },
            )
            DropdownMenuItem(text = { Text("Inbox") }, onClick = { open = false; onInbox() })
            DropdownMenuItem(text = { Text("Open in browser") }, onClick = { open = false; onOpenOutside() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Engine debug") }, onClick = { open = false; onDebug() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubredditBar(
    account: Account,
    location: Links.Location,
    syncing: Boolean,
    onOpen: (String) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "fixed:home") {
                FilterChip(
                    selected = location.isHome,
                    onClick = { onOpen(Links.HOME) },
                    label = { Text("Home") },
                )
            }
            items(listOf("popular", "all"), key = { "fixed:$it" }) { name ->
                FilterChip(
                    selected = location.subreddit == name,
                    onClick = { onOpen(Links.subreddit(name)) },
                    label = { Text(name.replaceFirstChar { it.uppercase() }) },
                )
            }
            when {
                account.username == null -> item(key = "hint:signin") {
                    AssistChip(
                        onClick = { onOpen(Links.LOGIN) },
                        label = { Text("Sign in to see your subreddits") },
                    )
                }
                account.subreddits.isEmpty() && syncing -> item(key = "hint:loading") {
                    AssistChip(onClick = {}, enabled = false, label = { Text("Loading subscriptions…") })
                }
            }
            items(account.subreddits, key = { "sub:" + it.lowercase() }) { name ->
                FilterChip(
                    selected = location.subreddit == name.lowercase(),
                    onClick = { onOpen(Links.subreddit(name)) },
                    label = { Text(name) },
                )
            }
        }
    }
}

/**
 * Keeps the engine's WebView attached to the window (WebViews misbehave when fully
 * detached). Normally it's a 1dp invisible speck; Engine debug shows it full size
 * along with its log and last extraction result.
 */
@Composable
private fun BoxScope.EngineHost(
    engine: RedditEngine,
    open: Boolean,
    syncing: Boolean,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    if (!open) {
        AndroidView(
            factory = { engine.webView.detached() },
            modifier = Modifier.size(1.dp).alpha(0f).align(Alignment.TopStart),
        )
        return
    }
    Surface(Modifier.fillMaxSize(), tonalElevation = 2.dp) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Engine debug", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onSync, enabled = !syncing) {
                    Text(if (syncing) "Syncing…" else "Run sync")
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            AndroidView(
                factory = { engine.webView.detached() },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            HorizontalDivider()
            SelectionContainer {
                Text(
                    text = buildString {
                        engine.log.takeLast(12).forEach { appendLine(it) }
                        appendLine()
                        append(engine.lastResult ?: "No extraction yet.")
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

/** Web links open in a Custom Tab; mail/phone/etc. links go to whatever app handles them. */
private fun openExternal(context: Context, uri: Uri) {
    val scheme = uri.scheme?.lowercase()
    try {
        if (scheme == "http" || scheme == "https") {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}
