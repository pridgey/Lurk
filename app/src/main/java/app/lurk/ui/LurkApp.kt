package app.lurk.ui

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
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
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.lurk.data.DebugLog
import app.lurk.data.DiscoverScreen
import app.lurk.data.FeedScreen
import app.lurk.data.FixedFeed
import app.lurk.data.Naming
import app.lurk.data.RssProbe
import app.lurk.data.RedditRssService
import app.lurk.data.ServiceKind
import app.lurk.data.RedditAccess
import app.lurk.data.ThreadScreen
import app.lurk.data.UpdateState
import app.lurk.data.Updater
import androidx.compose.runtime.LaunchedEffect
import app.lurk.data.WebScreen
import app.lurk.web.BrowserController
import app.lurk.web.Scripts
import android.webkit.WebView
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
    val updater = remember { Updater(context.applicationContext) }
    var updateOpen by remember { mutableStateOf(false) }

    // Look for a new build on launch, and say so once if there is one.
    LaunchedEffect(updater) {
        updater.checkIfDue()
        val release = updater.available ?: return@LaunchedEffect
        val go = snackbar.showSnackbar("Lurk build ${release.build} is available", actionLabel = "Update")
        if (go == SnackbarResult.ActionPerformed) updateOpen = true
    }
    if (updateOpen) UpdateDialog(updater, onClose = { updater.dismiss(); updateOpen = false })

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
            AccountScreen(store, session, snackbar, updater, onUpdates = { updateOpen = true })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AccountScreen(
    store: AccountStore,
    session: AccountSession,
    snackbar: SnackbarHostState,
    updater: Updater? = null,
    onUpdates: () -> Unit = {},
) {
    CompositionLocalProvider(LocalNaming provides session.service.naming) {
        AccountContent(store, session, snackbar, updater, onUpdates)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AccountContent(
    store: AccountStore,
    session: AccountSession,
    snackbar: SnackbarHostState,
    updater: Updater?,
    onUpdates: () -> Unit,
) {
    val browser = session.browser
    val account = session.account ?: return
    val screen = session.current
    var debugOpen by remember { mutableStateOf(false) }
    var subredditsOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    var confirmRemovePin by remember { mutableStateOf(false) }
    var addingLemmy by remember { mutableStateOf(false) }
    var addingBluesky by remember { mutableStateOf(false) }
    var rssSetup by remember { mutableStateOf(false) }
    var addingRss by remember { mutableStateOf(false) }
    var accountsOpen by remember { mutableStateOf(false) }
    val service = session.service
    val links = session.links
    val naming = service.naming
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    // Reddit blocking Lurk's browser: Reddit pages open in Chrome tabs instead.
    val inChrome = session.inChrome
    val goHome: () -> Unit = { if (inChrome) session.openInChrome(RedditAccess.Urls.HOME) else session.reset(session.feed(null)) }

    // Back in the app after a while: an RSS feed checks for new posts.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) session.onResume() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val goCommunity: (String) -> Unit = { name ->
        if (inChrome) session.openInChrome(RedditAccess.Urls.community(name)) else session.reset(session.feed(name))
    }
    // RSS can't list communities; Reddit's own page can.
    val goDiscover: () -> Unit = { if (session.webInChrome) session.openInChrome(RedditAccess.Urls.COMMUNITIES) else session.reset(session.discover()) }

    val handlers = remember(session) {
        PostHandlers(
            openThread = { post ->
                if (session.webInChrome) session.openWeb(post.shareUrl ?: session.links.thread(post.threadRef))
                else session.push(session.thread(post.threadRef, post))
            },
            openLink = { url -> session.openLink(url) },
            openImage = { url, preview ->
                session.viewingPreview = preview?.takeIf { it != url }
                session.viewingImage = url
            },
            openCommunity = { name ->
                if (session.inChrome) session.openInChrome(RedditAccess.Urls.community(name)) else session.push(session.feed(name))
            },
            openUser = { name -> session.openWeb(session.links.user(name)) },
            openRef = { ref -> if (session.webInChrome) session.openWeb(session.links.thread(ref)) else session.push(session.thread(ref)) },
            openOnWeb = { post -> session.openWeb(session.links.thread(post.threadRef)) },
            share = { post -> sharePost(context, post, session.links.share(post)) },
            openVideo = { post, startMs -> session.openVideo(post, startMs) },
            inlineVideo = InlineVideo(),
        )
    }

    /** The web address of what's on screen, for "View on web" and "Open in browser". */
    val currentUrl: String? = when (screen) {
        is FeedScreen -> screen.webUrl
        is ThreadScreen -> screen.webUrl
        is DiscoverScreen -> screen.webUrl
        is WebScreen -> browser.url
    }
    val webBack = screen is WebScreen && browser.canGoBack

    // From a community picked in the bottom bar, Back goes Home before leaving the app.
    val backToHome = session.stack.size == 1 &&
        ((screen is FeedScreen && screen.community != null) || screen is DiscoverScreen)
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
    /** "c/linux" rather than "c/linux@lemmy.ml", unless a subscription shares the name. */
    fun shortCommunity(name: String) = naming.community(naming.chip(name, account.communities))
    val title = when (screen) {
        is FeedScreen -> screen.community
            ?.takeIf { c -> service.fixedFeeds.none { it.community == c } }
            ?.let(::shortCommunity)
            ?: screen.label
        is ThreadScreen -> screen.post?.let { post -> post.community?.let(::shortCommunity) } ?: "Comments"
        is DiscoverScreen -> "Discover"
        is WebScreen -> links.locate(browser.url).label
    }.let { if (inChrome) "Reddit" else it }
    val titleCommunity = when (screen) {
        is FeedScreen -> screen.community?.let { c ->
            service.fixedFeeds.firstOrNull { it.community == c }?.label?.lowercase() ?: c
        }
        is ThreadScreen -> screen.post?.community
        is DiscoverScreen -> null
        is WebScreen -> null
    }?.takeIf { !inChrome }

    val selectedFeed: String? = when (screen) {
        is FeedScreen -> if (screen.community == null) HOME_KEY else screen.community.lowercase()
        is ThreadScreen -> screen.post?.community?.lowercase()
        is DiscoverScreen -> DISCOVER_KEY
        is WebScreen -> links.locate(browser.url).let { if (it.isHome) HOME_KEY else it.community }
    }
    // Tapping the title on a post (or a community's web page) opens that community's feed.
    val titleTarget: String? = when (screen) {
        is FeedScreen -> null
        is ThreadScreen -> screen.post?.community
        is DiscoverScreen -> null
        is WebScreen -> links.locate(browser.url).community
    }?.takeIf { !inChrome }

    fun toggleSubscription(community: String) {
        val label = naming.community(community)
        if (!session.isSubscribed(community)) {
            session.setSubscribed(community, true) { onMessage("Subscribed to $label") }
            return
        }
        session.setSubscribed(community, false) {
            scope.launch {
                val undo = snackbar.showSnackbar("Unsubscribed from $label", actionLabel = "Undo")
                if (undo == SnackbarResult.ActionPerformed) session.setSubscribed(community, true)
            }
        }
    }

    if (accountsOpen) {
        AccountSheet(
            current = account,
            accounts = store.visibleAccounts,
            hidden = { store.needsPin(it.id) },
            multiProfile = store.multiProfile,
            syncing = session.syncing,
            syncProblem = session.syncProblem.takeIf { !inChrome },
            authNote = if (inChrome) "Reddit is blocking Lurk's built-in browser, so Reddit opens in Chrome. Your account is fine." else session.authNote,
            actions = AccountActions(
                signIn = { session.signIn() },
                retrySync = { session.requestSync() },
                switchTo = { store.setActive(it.id) },
                signInTo = { a ->
                    store.requestLogin(a.id) // the new session opens on its sign-in page
                    store.setActive(a.id)
                },
                addReddit = {
                    if (!account.signedIn && !account.expired && account.kind == ServiceKind.Reddit) {
                        session.signIn() // reuse this empty slot
                    } else {
                        val added = store.add(ServiceKind.Reddit)
                        store.requestLogin(added.id)
                        store.setActive(added.id)
                    }
                },
                addRedditRss = { addingRss = true },
                addLemmy = { addingLemmy = true },
                addBluesky = { addingBluesky = true },
                setNsfw = { on -> store.update(account.id) { it.copy(showNsfw = on) } },
                setPin = { settingPin = true },
                removePin = { confirmRemovePin = true },
                signOut = { confirmSignOut = true },
                setStandardReddit = { on -> store.changeStandardReddit(on) },
            ),
            standardReddit = store.standardReddit,
            onDismiss = { accountsOpen = false },
        )
    }

    if (subredditsOpen) {
        SubredditSheet(
            subreddits = account.communities,
            naming = naming,
            fixedFeeds = service.fixedFeeds,
            selected = selectedFeed,
            homeKey = HOME_KEY,
            discoverKey = DISCOVER_KEY,
            signedIn = account.username != null,
            syncing = session.syncing,
            onHome = goHome,
            onSubreddit = goCommunity,
            onDiscover = goDiscover,
            onSignIn = { session.signIn() },
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (titleTarget != null) {
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClickLabel = "Open ${naming.community(titleTarget)}") {
                                    session.push(session.feed(titleTarget))
                                }
                        } else {
                            Modifier
                        },
                    ) {
                        when {
                            titleCommunity != null -> Avatar(naming.label(titleCommunity), 36.dp)
                            screen is FeedScreen -> HomeBadge()
                            screen is DiscoverScreen -> HomeBadge(LurkIcons.Compass)
                        }
                        val badged = titleCommunity != null || screen is FeedScreen || screen is DiscoverScreen
                        Column(Modifier.padding(start = if (badged) 12.dp else 0.dp)) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                when {
                                    account.expired -> "${account.label} · signed out"
                                    else -> account.label ?: account.instance?.let { "Not signed in · $it" } ?: "Not signed in"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (!inChrome && screen is FeedScreen && session.canSubscribe(screen.community)) {
                        val community = screen.community!!
                        val subscribed = session.isSubscribed(community)
                        IconButton(onClick = { toggleSubscription(community) }) {
                            Icon(
                                if (subscribed) Icons.Default.Check else Icons.Default.Add,
                                contentDescription = if (subscribed) "Unsubscribe from ${naming.community(community)}"
                                    else "Subscribe to ${naming.community(community)}",
                                tint = if (subscribed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (!inChrome) when (screen) {
                        is FeedScreen -> screen.sort?.let { sort ->
                            SortMenu(sort.label, service.feedSorts, { it.label }) { screen.changeSort(it) }
                        }
                        is ThreadScreen -> screen.sort?.let { sort ->
                            SortMenu(sort.label, service.commentSorts, { it.label }) { screen.changeSort(it) }
                        }
                        is DiscoverScreen -> screen.sort?.let { sort ->
                            SortMenu(sort.label, service.discoverSorts, { it.label }) { screen.changeSort(it) }
                        }
                        is WebScreen -> Unit
                    }
                    AccountButton(
                        current = account,
                        attention = account.expired || (session.syncProblem != null && !inChrome) || store.visibleAccounts.any { it.expired && !it.locked },
                        onClick = { accountsOpen = true },
                    )
                    OverflowMenu(
                        syncing = session.syncing,
                        onRefresh = {
                            when (screen) {
                                is FeedScreen -> screen.refresh()
                                is ThreadScreen -> screen.load()
                                is DiscoverScreen -> screen.refresh()
                                is WebScreen -> browser.reload()
                            }
                        },
                        onSync = { session.requestSync() },
                        onInbox = links.inbox?.let { inbox -> { session.openWeb(inbox) } },
                        onViewOnWeb = if (screen is WebScreen) null else ({ currentUrl?.let(session::openWeb) }),
                        onOpenOutside = { currentUrl?.let { openExternal(context, Uri.parse(it)) } },
                        onDebug = { debugOpen = true },
                        update = updater?.state,
                        onUpdates = updater?.let { u ->
                            {
                                if (u.available == null) u.startCheck()
                                onUpdates()
                            }
                        },
                    )
                },
            )
        },
        bottomBar = {
            SubredditBar(
                account = account,
                naming = naming,
                fixedFeeds = service.fixedFeeds,
                selected = selectedFeed,
                syncing = session.syncing,
                onHome = goHome,
                onSubreddit = goCommunity,
                onDiscover = goDiscover,
                onSignIn = { session.signIn() },
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
            if (session.isRss && !account.signedIn && screen is FeedScreen) {
                RssSignInView(onSignIn = { session.signIn() })
            } else if (inChrome) {
                RedditChromeView(
                    subreddits = account.communities,
                    onOpen = session::openInChrome,
                    onRetry = { session.retryLurkBrowser() },
                    onSetUpRss = { addingRss = true },
                )
            } else key(screen) {
                when (screen) {
                    is FeedScreen -> FeedView(
                        screen, session.actions, handlers,
                        onViewOnWeb = { session.openWeb(screen.webUrl) },
                        header = if (!session.isRss) null else ({ RssFeedHeader(screen) }),
                    )
                    is ThreadScreen -> ThreadView(screen, session.actions, handlers, onMessage)
                    is DiscoverScreen -> DiscoverView(
                        screen,
                        signedIn = account.username != null,
                        activeLabel = if (service.kind == ServiceKind.Reddit) "online" else "active this week",
                        onOpen = { name -> session.push(session.feed(name)) },
                        onMessage = onMessage,
                    )
                    is WebScreen -> WebPage(screen, browser)
                }
            }
            EngineHost(
                log = service.log,
                engineView = service.engineView,
                open = debugOpen,
                syncing = session.syncing,
                onSync = { session.requestSync() },
                onTestRss = { rssSetup = true }, // public feeds: no Reddit sign-in needed
                rssTesting = session.rssTesting,
                onClose = { debugOpen = false },
            )
        }
    }

    session.viewingImage?.let { url ->
        ImageViewer(url, session.viewingPreview, onClose = { session.viewingImage = null })
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
                    session.openWeb(links.thread(post.threadRef))
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

    if (addingRss) {
        RssLinkDialog(
            knownUser = null,
            suggested = RssProbe.savedFrontPage(context),
            onSave = { link ->
                addingRss = false
                val user = RedditRssService.userOf(link)
                val existing = store.accounts.firstOrNull {
                    it.kind == ServiceKind.RedditRss && (it.username ?: it.knownAs).equals(user, ignoreCase = true)
                }
                if (existing?.id == account.id) {
                    session.onRssLinked(link)
                } else if (existing != null) {
                    store.update(existing.id) { it.copy(username = user, knownAs = user, token = link) }
                    store.setActive(existing.id)
                    onMessage("${existing.label} is already added, switched to it")
                } else if (!account.signedIn && !account.expired && account.kind == ServiceKind.RedditRss) {
                    session.onRssLinked(link) // fill this empty slot
                } else {
                    val added = store.add(ServiceKind.RedditRss) { it.copy(username = user, knownAs = user, token = link) }
                    store.setActive(added.id)
                }
            },
            onCancel = { addingRss = false },
        )
    }

    if (session.signingIn && service.kind == ServiceKind.RedditRss) {
        RssLinkDialog(
            knownUser = account.knownAs,
            suggested = account.token ?: RssProbe.savedFrontPage(context),
            onSave = { link -> session.onRssLinked(link) },
            onCancel = { session.signingIn = false },
        )
    } else if (session.signingIn && service.kind == ServiceKind.Bluesky) {
        BlueskyLoginDialog(
            knownHandle = account.knownAs,
            onSignedIn = { login -> session.onBlueskySignedIn(login) },
            onCancel = { session.signingIn = false },
        )
    } else if (session.signingIn) {
        LemmyLoginDialog(
            instance = account.instance,
            knownUser = account.knownAs,
            onSignedIn = { _, username, token -> session.onSignedIn(username, token) },
            onCancel = { session.signingIn = false },
        )
    }
    if (addingLemmy) {
        LemmyLoginDialog(
            instance = null,
            onSignedIn = { instance, username, token ->
                addingLemmy = false
                val existing = store.accounts.firstOrNull {
                    it.kind == ServiceKind.Lemmy && it.instance == instance &&
                        (it.username ?: it.knownAs).equals(username, ignoreCase = true)
                }
                if (existing?.id == account.id) {
                    session.onSignedIn(username, token) // this account, signed back in
                } else if (existing != null) {
                    store.update(existing.id) { it.copy(username = it.username ?: username, knownAs = username, token = token) }
                    store.setActive(existing.id)
                    onMessage("${existing.label} is already added, switched to it")
                } else {
                    val added = store.add(ServiceKind.Lemmy) {
                        it.copy(username = username, instance = instance, token = token)
                    }
                    store.setActive(added.id)
                }
            },
            onCancel = { addingLemmy = false },
        )
    }

    if (addingBluesky) {
        BlueskyLoginDialog(
            knownHandle = null,
            onSignedIn = { login ->
                addingBluesky = false
                val existing = store.accounts.firstOrNull {
                    it.kind == ServiceKind.Bluesky && (it.username ?: it.knownAs).equals(login.handle, ignoreCase = true)
                }
                if (existing?.id == account.id) {
                    session.onBlueskySignedIn(login) // this account, signed back in
                } else if (existing != null) {
                    store.update(existing.id) {
                        it.copy(
                            username = it.username ?: login.handle, knownAs = login.handle, instance = login.pds,
                            token = login.accessToken, refreshToken = login.refreshToken,
                        )
                    }
                    store.setActive(existing.id)
                    onMessage("${existing.label} is already added, switched to it")
                } else {
                    val added = store.add(ServiceKind.Bluesky) {
                        it.copy(
                            username = login.handle, knownAs = login.handle, instance = login.pds,
                            token = login.accessToken, refreshToken = login.refreshToken,
                        )
                    }
                    store.setActive(added.id)
                }
            },
            onCancel = { addingBluesky = false },
        )
    }

    if (rssSetup) {
        RssTestDialog(
            saved = RssProbe.savedFrontPage(context),
            onRun = { frontPage ->
                rssSetup = false
                RssProbe.saveFrontPage(context, frontPage)
                session.testRss(frontPage)
            },
            onCancel = { rssSetup = false },
        )
    }

    if (settingPin) {
        SetPinDialog(
            username = account.username ?: account.knownAs,
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
            text = { Text("Anyone using this phone will be able to switch to ${account.label ?: "this account"}.") },
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
            title = { Text(if (account.signedIn) "Sign out of ${account.label}?" else "Remove ${account.label ?: "this account"}?") },
            text = { Text("This removes the account from Lurk on this phone. You can add it again any time.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    session.signOut()
                }) { Text(if (account.signedIn) "Sign out" else "Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }
}

private const val HOME_KEY = ":home" // can't clash with a community name
private const val DISCOVER_KEY = ":discover"

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
private fun OverflowMenu(
    syncing: Boolean,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    /** Null when the site has no inbox page. */
    onInbox: (() -> Unit)?,
    /** Null when already on the web. */
    onViewOnWeb: (() -> Unit)?,
    onOpenOutside: () -> Unit,
    onDebug: () -> Unit,
    update: UpdateState?,
    /** Null where updates aren't offered (previews). */
    onUpdates: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    val updateWaiting = update is UpdateState.Available || update is UpdateState.Downloading || update is UpdateState.Installing
    Box {
        IconButton(onClick = { open = true }) {
            Box {
                Icon(Icons.Default.MoreVert, contentDescription = if (updateWaiting) "More, update available" else "More")
                if (updateWaiting) AttentionDot(Modifier.align(Alignment.TopEnd), 9.dp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onUpdates != null && updateWaiting) {
                DropdownMenuItem(
                    text = {
                        Text(
                            when (update) {
                                is UpdateState.Downloading -> "Downloading update… ${update.progress?.let { "${(it * 100).toInt()}%" } ?: ""}"
                                is UpdateState.Installing -> "Installing update…"
                                is UpdateState.Available -> "Update to build ${update.release.build}"
                                else -> "Update"
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { open = false; onUpdates() },
                )
                HorizontalDivider()
            }
            DropdownMenuItem(text = { Text("Refresh page") }, onClick = { open = false; onRefresh() })
            DropdownMenuItem(
                text = { Text(if (syncing) "Syncing subscriptions…" else "Sync subscriptions") },
                enabled = !syncing,
                onClick = { open = false; onSync() },
            )
            if (onInbox != null) {
                DropdownMenuItem(text = { Text("Inbox") }, onClick = { open = false; onInbox() })
            }
            if (onViewOnWeb != null) {
                DropdownMenuItem(text = { Text("View on web") }, onClick = { open = false; onViewOnWeb() })
            }
            DropdownMenuItem(text = { Text("Open in browser") }, onClick = { open = false; onOpenOutside() })
            HorizontalDivider()
            if (onUpdates != null && !updateWaiting) {
                DropdownMenuItem(text = { Text("Check for updates") }, onClick = { open = false; onUpdates() })
            }
            DropdownMenuItem(text = { Text("Engine debug") }, onClick = { open = false; onDebug() })
        }
    }
}

@Composable
private fun SubredditBar(
    account: Account,
    naming: Naming,
    fixedFeeds: List<FixedFeed>,
    /** Lowercase community name, [HOME_KEY], or null. */
    selected: String?,
    syncing: Boolean,
    onHome: () -> Unit,
    onSubreddit: (String) -> Unit,
    onDiscover: () -> Unit,
    onSignIn: () -> Unit,
    /** Opens the drawer with every community. */
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
                    .clickable(onClickLabel = "All ${naming.communityNoun.plural()}", onClick = onOpenAll)
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
                items(fixedFeeds, key = { "fixed:${it.community}" }) { feed ->
                    BarChip(feed.label, selected == feed.community.lowercase(), { onSubreddit(feed.community) }, avatar = feed.label.lowercase())
                }
                item(key = "fixed:discover") {
                    BarChip("Discover", selected == DISCOVER_KEY, onDiscover) {
                        Icon(LurkIcons.Compass, null, Modifier.size(18.dp))
                    }
                }
                when {
                    account.expired -> item(key = "hint:signin") {
                        BarChip("Signed out · Sign in again", false, onSignIn, accent = true)
                    }
                    !account.signedIn -> item(key = "hint:signin") {
                        BarChip("Sign in to see your ${naming.communityNoun.plural()}", false, onSignIn, accent = true)
                    }
                    account.communities.isEmpty() && syncing -> item(key = "hint:loading") {
                        BarChip("Loading subscriptions…", false, {})
                    }
                }
                items(account.communities, key = { "sub:" + it.lowercase() }) { name ->
                    BarChip(naming.chip(name, account.communities), selected == name.lowercase(), { onSubreddit(name) }, avatar = naming.label(name))
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

/** The title badge for Home, or another screen with an icon instead of a community. */
@Composable
private fun HomeBadge(icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Home) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
    }
}

/**
 * Keeps the engine's WebView attached to the window (WebViews misbehave when fully
 * detached). Normally it's a 1dp invisible speck; Engine debug shows it full size
 * along with its log and last extraction result.
 */
@Composable
private fun BoxScope.EngineHost(
    log: DebugLog,
    /** The service's hidden WebView, if it has one. */
    engineView: WebView?,
    open: Boolean,
    syncing: Boolean,
    onSync: () -> Unit,
    /** Checks Reddit's RSS rate limit from this phone, from any account. */
    onTestRss: (() -> Unit)?,
    rssTesting: Boolean,
    onClose: () -> Unit,
) {
    if (!open) {
        if (engineView != null) {
            AndroidView(
                factory = { engineView.detached() },
                modifier = Modifier.size(1.dp).alpha(0f).align(Alignment.TopStart),
            )
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    val report = buildString {
        log.lines.forEach { appendLine(it) }
        appendLine()
        append(log.lastResult ?: "No response yet.")
    }
    Surface(Modifier.fillMaxSize(), tonalElevation = 2.dp) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Engine debug", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (onTestRss != null) {
                    TextButton(onClick = onTestRss, enabled = !rssTesting) {
                        Text(if (rssTesting) "Testing…" else "Test RSS")
                    }
                }
                TextButton(onClick = onSync, enabled = !syncing) {
                    Text(if (syncing) "Syncing…" else "Run sync")
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy") }
                TextButton(onClick = onClose) { Text("Close") }
            }
            if (engineView != null) {
                AndroidView(
                    factory = { engineView.detached() },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                HorizontalDivider()
            }
            SelectionContainer {
                Text(
                    text = report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (engineView != null) Modifier.height(220.dp) else Modifier.weight(1f))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

/** Shares a post's title and link through the system share sheet. */
private fun sharePost(context: Context, post: Post, link: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, post.title)
        .putExtra(Intent.EXTRA_TEXT, link)
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
