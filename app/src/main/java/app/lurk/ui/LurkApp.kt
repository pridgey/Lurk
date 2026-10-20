package app.lurk.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import app.lurk.data.Post
import app.lurk.data.PostMedia
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
    val active = store.find(activeId)

    // Keep a PIN account's screens out of the recent-apps preview (and screenshots).
    val secure = active?.locked == true
    DisposableEffect(secure) {
        val window = (context as? Activity)?.window
        if (secure) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    if (store.needsPin(activeId)) {
        val elsewhere = store.elsewhere()
        LockScreen(
            onLeave = elsewhere?.let { id -> { store.setActive(id) } },
            onUnlock = { pin -> store.unlock(activeId, pin) },
            onForget = { store.forget(activeId) },
        )
        return
    }

    key(activeId) {
        val session = remember {
            AccountSession(
                context, store, activeId, scripts, onMessage, onExternal,
                signInFirst = store.consumePendingLogin(activeId),
            )
        }
        DisposableEffect(session) {
            session.start()
            onDispose { session.destroy() }
        }
        CompositionLocalProvider(LocalShowNsfw provides (active?.showNsfw == true)) {
            AccountScreen(store, session, snackbar)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AccountScreen(store: AccountStore, session: AccountSession, snackbar: SnackbarHostState) {
    val browser = session.browser
    val account = session.account ?: return
    val screen = session.current
    var debugOpen by remember { mutableStateOf(false) }
    var subredditsOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    var confirmRemovePin by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    val handlers = remember(session) {
        PostHandlers(
            openThread = { post -> session.push(session.thread(post.permalink, post)) },
            openLink = { url -> session.openLink(url) },
            openImage = { url -> session.viewingImage = url },
            openSubreddit = { name -> session.push(session.feed(name)) },
            openUser = { name -> session.openWeb(Links.user(name)) },
            openOnWeb = { post -> session.openWeb(Links.webOrigin + post.permalink) },
            share = { post -> sharePost(context, post) },
            openVideo = { post, startMs -> session.openVideo(post, startMs) },
            inlineVideo = InlineVideo(),
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

    // The top bar slides away while scrolling down a list and comes back on the way up.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val title = when (screen) {
        is FeedScreen -> screen.label
        is ThreadScreen -> screen.post?.let { "r/${it.subreddit}" } ?: "Comments"
        is WebScreen -> Links.locate(browser.url).label
    }
    val titleSubreddit = when (screen) {
        is FeedScreen -> screen.subreddit
        is ThreadScreen -> screen.post?.subreddit
        is WebScreen -> null
    }

    val selectedFeed: String? = when (screen) {
        is FeedScreen -> if (screen.subreddit == null) HOME_KEY else screen.subreddit.lowercase()
        is ThreadScreen -> screen.post?.subreddit?.lowercase()
        is WebScreen -> Links.locate(browser.url).let { if (it.isHome) HOME_KEY else it.subreddit }
    }
    if (subredditsOpen) {
        SubredditSheet(
            subreddits = account.subreddits,
            selected = selectedFeed,
            homeKey = HOME_KEY,
            signedIn = account.username != null,
            syncing = session.syncing,
            onHome = { session.reset(session.feed(null)) },
            onSubreddit = { session.reset(session.feed(it)) },
            onSignIn = { session.openWeb(Links.LOGIN) },
            onDismiss = { subredditsOpen = false },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    if (session.stack.size > 1) {
                        IconButton(onClick = { session.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when {
                            titleSubreddit != null -> Avatar(titleSubreddit, 36.dp)
                            screen is FeedScreen -> HomeBadge()
                        }
                        Column(Modifier.padding(start = if (titleSubreddit != null || screen is FeedScreen) 12.dp else 0.dp)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                account.username?.let { "u/$it" } ?: "Not signed in",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                        onSetPin = { settingPin = true },
                        onRemovePin = { confirmRemovePin = true },
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
                selected = selectedFeed,
                syncing = session.syncing,
                onHome = { session.reset(session.feed(null)) },
                onSubreddit = { session.reset(session.feed(it)) },
                onSignIn = { session.openWeb(Links.LOGIN) },
                onOpenAll = { subredditsOpen = true },
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

    session.playingFile?.let { stream ->
        VideoPlayer(
            stream,
            onClose = { session.playingFile = null },
            onOpenOnWeb = {
                session.playingFile = null
                stream.mp4Url?.let { openExternal(context, Uri.parse(it)) }
            },
        )
    }

    session.sheetUrl?.let { url ->
        VideoSheet(
            url,
            isolateScript = session.scripts.isolate,
            onClose = { session.sheetUrl = null },
            onOpenOutside = { uri ->
                session.sheetUrl = null
                openExternal(context, uri)
            },
        )
    }

    session.playingVideo?.let { post ->
        val media = post.media as? PostMedia.Video
        val stream = media?.stream
        val embed = media?.embed
        when {
            stream != null -> VideoPlayer(
                stream,
                startMs = session.playingVideoStartMs,
                onClose = { session.playingVideo = null },
                onOpenOnWeb = {
                    session.playingVideo = null
                    session.openWeb(Links.webOrigin + post.permalink)
                },
            )
            embed != null -> EmbedPlayer(
                embed,
                siteName = embed.provider ?: post.domain.removePrefix("www."),
                onClose = { session.playingVideo = null },
                onOpenSite = {
                    session.playingVideo = null
                    openExternal(context, Uri.parse(post.url))
                },
                onExternalLink = { uri -> openExternal(context, uri) },
            )
        }
    }

    if (settingPin) {
        SetPinDialog(
            username = account.username,
            onSet = { pin ->
                settingPin = false
                store.setPin(account.id, pin)
                onMessage("PIN set. Switching to this account will ask for it.")
            },
            onCancel = { settingPin = false },
        )
    }
    if (confirmRemovePin) {
        AlertDialog(
            onDismissRequest = { confirmRemovePin = false },
            title = { Text("Remove the PIN?") },
            text = { Text("Anyone using this phone will be able to switch to ${account.username?.let { "u/$it" } ?: "this account"}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemovePin = false
                    store.clearPin(account.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemovePin = false }) { Text("Cancel") } },
        )
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
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(end = 4.dp).clip(CircleShape).clickable { open = true },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(LurkIcons.Sort, contentDescription = "Sort", modifier = Modifier.size(16.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp).widthIn(max = 96.dp),
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            val name = current.username
            if (name != null) {
                Avatar(name, 32.dp)
            } else {
                Icon(Icons.Default.AccountCircle, contentDescription = "Accounts", modifier = Modifier.size(30.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            store.accounts.forEach { a ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when {
                                store.needsPin(a.id) -> "Private account"
                                else -> a.username?.let { "u/$it" } ?: "Not signed in"
                            },
                        )
                    },
                    leadingIcon = {
                        if (a.id == current.id) Icon(Icons.Default.Check, contentDescription = "Active")
                        else Spacer(Modifier.size(24.dp))
                    },
                    trailingIcon = if (a.locked) {
                        { Icon(Icons.Default.Lock, contentDescription = "Needs PIN", Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        store.setActive(a.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Show NSFW without tapping") },
                leadingIcon = {
                    if (current.showNsfw) Icon(Icons.Default.Check, contentDescription = "On")
                    else Spacer(Modifier.size(24.dp))
                },
                onClick = { open = false; store.update(current.id) { it.copy(showNsfw = !it.showNsfw) } },
            )
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
                    text = { Text(if (current.locked) "Remove PIN" else "Require PIN to switch here") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    onClick = { open = false; if (current.locked) onRemovePin() else onSetPin() },
                )
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
    /** Opens the drawer with every subreddit. */
    onOpenAll: () -> Unit,
) {
    // A swipe up anywhere on the bar opens the drawer.
    var dragged by remember { mutableFloatStateOf(0f) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.draggable(
            orientation = Orientation.Vertical,
            state = rememberDraggableState { dragged += it },
            onDragStarted = { dragged = 0f },
            onDragStopped = { velocity -> if (dragged < -24f || velocity < -600f) onOpenAll() },
        ),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "All subreddits", onClick = onOpenAll)
                    .padding(top = 8.dp, bottom = 2.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "fixed:home") {
                    BarChip("Home", selected == HOME_KEY, onHome) {
                        Icon(Icons.Default.Home, null, Modifier.size(18.dp))
                    }
                }
                items(listOf("popular", "all"), key = { "fixed:$it" }) { name ->
                    BarChip(name.replaceFirstChar { it.uppercase() }, selected == name, { onSubreddit(name) }, avatar = name)
                }
                when {
                    account.username == null -> item(key = "hint:signin") {
                        BarChip("Sign in to see your subreddits", false, onSignIn, accent = true)
                    }
                    account.subreddits.isEmpty() && syncing -> item(key = "hint:loading") {
                        BarChip("Loading subscriptions…", false, {})
                    }
                }
                items(account.subreddits, key = { "sub:" + it.lowercase() }) { name ->
                    BarChip(name, selected == name.lowercase(), { onSubreddit(name) }, avatar = name)
                }
            }
        }
    }
}

/** A pill in the subreddit bar: filled with the accent when selected. */
@Composable
private fun BarChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Boolean = false,
    /** Shows this name's avatar before the label (white on the selected chip). */
    avatar: String? = null,
    leading: (@Composable () -> Unit)? = avatar?.let { name ->
        { Avatar(name, 20.dp, tint = if (selected) MaterialTheme.colorScheme.onPrimary else null) }
    },
) {
    val container = when {
        selected -> MaterialTheme.colorScheme.primary
        accent -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        accent -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier.height(38.dp).clip(CircleShape).clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = if (leading != null) 8.dp else 14.dp, end = 14.dp),
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** The Home feed's title badge. */
@Composable
private fun HomeBadge() {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Home, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
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
    val clipboard = LocalClipboardManager.current
    val report = buildString {
        engine.log.forEach { appendLine(it) }
        appendLine()
        append(engine.lastResult ?: "No response yet.")
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
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy") }
                TextButton(onClick = onClose) { Text("Close") }
            }
            AndroidView(
                factory = { engine.webView.detached() },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            HorizontalDivider()
            SelectionContainer {
                Text(
                    text = report,
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

/** Shares a post's title and link through the system share sheet. */
private fun sharePost(context: Context, post: Post) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, post.title)
        .putExtra(Intent.EXTRA_TEXT, "https://www.reddit.com" + post.permalink)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
