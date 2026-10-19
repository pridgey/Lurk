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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
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
import app.lurk.data.CommentSort
import app.lurk.data.FeedScreen
import app.lurk.data.FeedSort
import app.lurk.data.ThreadScreen
import app.lurk.data.WebScreen
import app.lurk.web.BrowserController
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
    val screen = session.current
    var debugOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    val handlers = remember(session) {
        PostHandlers(
            openThread = { post -> session.push(session.thread(post.permalink, post)) },
            openLink = { url -> session.openLink(url) },
            openImage = { url -> session.viewingImage = url },
            openSubreddit = { name -> session.push(session.feed(name)) },
            openUser = { name -> session.openWeb("${Links.OLD_ORIGIN}/user/$name/") },
            openOnWeb = { post -> session.openWeb(Links.OLD_ORIGIN + post.permalink) },
        )
    }

    /** The web address of what's on screen, for "View on web" and "Open in browser". */
    val currentUrl: String? = when (screen) {
        is FeedScreen -> screen.webUrl
        is ThreadScreen -> screen.webUrl
        is WebScreen -> browser.url
    }
    val webBack = screen is WebScreen && browser.canGoBack

    // From a subreddit picked in the bottom bar, Back goes Home before leaving the app.
    val backToHome = session.stack.size == 1 && screen is FeedScreen && screen.subreddit != null
    BackHandler(enabled = debugOpen || webBack || session.stack.size > 1 || backToHome) {
        when {
            debugOpen -> debugOpen = false
            webBack -> browser.goBack()
            backToHome -> session.reset(session.feed(null))
            else -> session.pop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (session.stack.size > 1) {
                        IconButton(onClick = { session.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            when (screen) {
                                is FeedScreen -> screen.label
                                is ThreadScreen -> screen.post?.let { "r/${it.subreddit}" } ?: "Comments"
                                is WebScreen -> Links.locate(browser.url).label
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            account.username?.let { "u/$it" } ?: "Not signed in",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    when (screen) {
                        is FeedScreen -> SortMenu(screen.sort.label, FeedSort.entries, { it.label }) { screen.changeSort(it) }
                        is ThreadScreen -> SortMenu(screen.sort.label, CommentSort.entries, { it.label }) { screen.changeSort(it) }
                        is WebScreen -> Unit
                    }
                    AccountMenu(
                        store = store,
                        current = account,
                        onSignIn = { session.openWeb(Links.LOGIN) },
                        onAdd = {
                            if (account.username == null) {
                                session.openWeb(Links.LOGIN) // reuse this empty slot
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
                        onRefresh = {
                            when (screen) {
                                is FeedScreen -> screen.refresh()
                                is ThreadScreen -> screen.load()
                                is WebScreen -> browser.reload()
                            }
                        },
                        onSync = { session.requestSync() },
                        onInbox = { session.openWeb(Links.INBOX) },
                        onViewOnWeb = if (screen is WebScreen) null else ({ currentUrl?.let(session::openWeb) }),
                        onOpenOutside = { currentUrl?.let { openExternal(context, Uri.parse(it)) } },
                        onDebug = { debugOpen = true },
                    )
                },
            )
        },
        bottomBar = {
            SubredditBar(
                account = account,
                selected = when (screen) {
                    is FeedScreen -> if (screen.subreddit == null) HOME_KEY else screen.subreddit.lowercase()
                    is ThreadScreen -> screen.post?.subreddit?.lowercase()
                    is WebScreen -> Links.locate(browser.url).let { if (it.isHome) HOME_KEY else it.subreddit }
                },
                syncing = session.syncing,
                onHome = { session.reset(session.feed(null)) },
                onSubreddit = { session.reset(session.feed(it)) },
                onSignIn = { session.openWeb(Links.LOGIN) },
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
            key(screen) {
                when (screen) {
                    is FeedScreen -> FeedView(
                        screen, session.actions, handlers,
                        onViewOnWeb = { session.openWeb(screen.webUrl) },
                    )
                    is ThreadScreen -> ThreadView(screen, session.actions, handlers, onMessage)
                    is WebScreen -> WebPage(screen, browser)
                }
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

    session.viewingImage?.let { url ->
        ImageViewer(url, onClose = { session.viewingImage = null })
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

private const val HOME_KEY = ":home" // can't clash with a subreddit name

/**
 * The visible WebView, for a [WebScreen]. Several web screens can be on the stack but
 * share one WebView, so each remembers where it was and reloads that when it's back on top.
 */
@Composable
private fun BoxScope.WebPage(screen: WebScreen, browser: BrowserController) {
    DisposableEffect(screen) {
        if (browser.url != screen.url) browser.openFresh(screen.url)
        onDispose { browser.url?.let { screen.url = it } }
    }
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
}

@Composable
private fun <T> SortMenu(label: String, options: List<T>, name: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(label, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(name(option)) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
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
    /** Null when already on the web. */
    onViewOnWeb: (() -> Unit)?,
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
            if (onViewOnWeb != null) {
                DropdownMenuItem(text = { Text("View on web") }, onClick = { open = false; onViewOnWeb() })
            }
            DropdownMenuItem(text = { Text("Open in browser") }, onClick = { open = false; onOpenOutside() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Engine debug") }, onClick = { open = false; onDebug() })
        }
    }
}

@Composable
private fun SubredditBar(
    account: Account,
    /** Lowercase subreddit name, [HOME_KEY], or null. */
    selected: String?,
    syncing: Boolean,
    onHome: () -> Unit,
    onSubreddit: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "fixed:home") {
                FilterChip(selected = selected == HOME_KEY, onClick = onHome, label = { Text("Home") })
            }
            items(listOf("popular", "all"), key = { "fixed:$it" }) { name ->
                FilterChip(
                    selected = selected == name,
                    onClick = { onSubreddit(name) },
                    label = { Text(name.replaceFirstChar { it.uppercase() }) },
                )
            }
            when {
                account.username == null -> item(key = "hint:signin") {
                    AssistChip(onClick = onSignIn, label = { Text("Sign in to see your subreddits") })
                }
                account.subreddits.isEmpty() && syncing -> item(key = "hint:loading") {
                    AssistChip(onClick = {}, enabled = false, label = { Text("Loading subscriptions…") })
                }
            }
            items(account.subreddits, key = { "sub:" + it.lowercase() }) { name ->
                FilterChip(
                    selected = selected == name.lowercase(),
                    onClick = { onSubreddit(name) },
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
