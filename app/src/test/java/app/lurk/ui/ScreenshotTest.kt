package app.lurk.ui

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lurk.data.AccountSession
import app.lurk.data.AccountStore
import app.lurk.data.Comment
import app.lurk.data.FeedScreen
import app.lurk.data.RedditSorts
import app.lurk.data.More
import app.lurk.data.Picture
import app.lurk.data.Post
import app.lurk.data.PostMedia
import app.lurk.data.ThreadScreen
import app.lurk.web.Scripts
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens with sample data to PNGs in app/build/screenshots, for
 * looking at the design without a device. Images don't load (no network), so media
 * shows its placeholder.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = System.currentTimeMillis() / 1000

    private fun post(
        id: String, title: String, sub: String, score: Int, comments: Int, hoursAgo: Int,
        selftext: String = "", media: PostMedia? = null, thumbnail: String? = null,
        domain: String = "self.$sub", flair: String? = null, likes: Boolean? = null,
        over18: Boolean = false, stickied: Boolean = false, saved: Boolean = false,
    ) = Post(
        name = "t3_$id", id = id, title = title, author = "user_$id", community = sub,
        score = score, likes = likes, saved = saved, numComments = comments,
        createdUtc = now - hoursAgo * 3600L, threadRef = "/r/$sub/comments/$id/x/",
        url = "https://$domain/x", domain = domain, isSelf = selftext.isNotEmpty() || (media == null && thumbnail == null),
        selftext = selftext, selftextHtml = if (selftext.isEmpty()) null else "<div class=\"md\"><p>$selftext</p><p>Second paragraph with a <a href=\"https://example.com\">link</a>.</p></div>",
        flair = flair, over18 = over18, spoiler = false, stickied = stickied, locked = false,
        media = media, thumbnail = thumbnail,
    )

    private val pic = Picture("https://x/p.jpg", "https://x/p.jpg", 1600, 1000)

    private val posts = listOf(
        post("a1", "Weekly discussion thread: what are you building this week?", "androiddev", 214, 87, 2,
            selftext = "Share what you're working on, ask for feedback, or just say hi. Be kind and keep it constructive.",
            stickied = true, flair = "Discussion"),
        post("a2", "Sunrise over the Dolomites after a 4am hike", "EarthPorn", 18432, 412, 5, media = PostMedia.Image(pic), likes = true),
        post("v1", "Timelapse of a storm rolling over the plains", "videos", 3204, 98, 6,
            media = PostMedia.Video(pic, app.lurk.data.VideoStream("https://v.redd.it/x.m3u8", null, 1920, 1080, 83, false))),
        post("a3", "Jetpack Compose 2.0 roadmap announced", "androiddev", 1290, 233, 8,
            thumbnail = "https://x/t.jpg", domain = "android-developers.googleblog.com", flair = "News"),
        post("a4", "My grandfather's workshop, untouched since 1987", "pics", 9021, 301, 11,
            media = PostMedia.Gallery(listOf(pic, pic, pic)), saved = true),
        post("a5", "Is it worth learning Kotlin Multiplatform in 2026?", "Kotlin", 87, 64, 14,
            selftext = "I've been doing Android for a few years and wondering whether KMP is mature enough now for a production app with a shared UI layer."),
    )

    private fun session(): AccountSession {
        val store = AccountStore(context, multiProfile = false)
        store.update(store.activeId) {
            it.copy(username = "lurker", communities = listOf("androiddev", "AskReddit", "EarthPorn", "Kotlin", "pics", "science"))
        }
        return AccountSession(context, store, store.activeId, Scripts(context), {}, {}, signInFirst = false)
    }

    private fun lemmySession(): AccountSession {
        val store = AccountStore(context, multiProfile = false)
        store.update(store.activeId) {
            it.copy(
                kind = app.lurk.data.ServiceKind.Lemmy, instance = "lemmy.world", username = "lurker",
                communities = listOf("technology", "linux@lemmy.ml", "memes@sopuli.xyz", "photography"),
            )
        }
        return AccountSession(context, store, store.activeId, Scripts(context), {}, {}, signInFirst = false)
    }

    private fun shoot(name: String, dark: Boolean, makeSession: () -> AccountSession = ::session, setup: (AccountSession) -> Unit) {
        val s = makeSession()
        setup(s)
        val store = AccountStore(context, multiProfile = false)
        // Spinners never settle, so step the clock instead of waiting for idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LurkTheme(darkOverride = dark) { AccountScreen(store, s, SnackbarHostState()) }
        }
        compose.mainClock.advanceTimeBy(2_000)
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun feed(s: AccountSession, sub: String?, from: Int = 0) =
        FeedScreen(sub, RedditSorts.feed.first(), s.service, kotlinx.coroutines.MainScope()).also { it.posts.addAll(posts.drop(from)) }

    @Test fun feedLight() = shoot("feed", dark = false) { it.reset(feed(it, null)) }
    @Test fun feedDark() = shoot("feed", dark = true) { it.reset(feed(it, null)) }
    @Test fun feedLinksLight() = shoot("feed-links", dark = false) { it.reset(feed(it, "androiddev", from = 2)) }
    @Test fun loadingLight() = shoot("loading", dark = false) {
        it.reset(FeedScreen("pics", RedditSorts.feed.first(), it.service, kotlinx.coroutines.MainScope()).also { f -> f.loadMore() })
    }

    private fun thread(s: AccountSession): ThreadScreen {
        val t = ThreadScreen("/r/Kotlin/comments/a5/x/", posts[4], s.service, kotlinx.coroutines.MainScope())
        fun c(id: String, depth: Int, parent: String, body: String, score: Int, op: Boolean = false, hours: Int = 3) =
            Comment("t1_$id", "commenter_$id", "<div class=\"md\"><p>$body</p></div>", score, false, null, false,
                now - hours * 3600L, depth, parent, op, false, null, null)
        t.items.addAll(listOf(
            c("c1", 0, "t3_a5", "Yes. The tooling has gotten much better and Compose Multiplatform is stable on iOS now.", 142),
            c("c2", 1, "t1_c1", "Agreed, though I'd still keep platform-specific UI for anything heavily animated.", 58),
            c("c3", 2, "t1_c2", "That's fair. We share view models and keep the UI native.", 21, op = true),
            More("t1_m", listOf("x", "y"), 2, 3, "t1_c3"),
            c("c4", 0, "t3_a5", "Depends on your team. If nobody knows Swift, it's a big win.", 33, hours = 5),
            c("c5", 1, "t1_c4", "This. Hiring for two native teams is expensive.", 12, hours = 4),
        ))
        return t
    }

    @Test fun threadLight() = shoot("thread", dark = false) { it.reset(thread(it)) }

    private fun lemmyFeed(s: AccountSession) = FeedScreen(null, s.service.feedSorts.first(), s.service, kotlinx.coroutines.MainScope()).also {
        it.posts.addAll(posts.take(3).map { p -> p.copy(name = "p" + p.id, community = if (p.id == "a2") "photography" else "linux@lemmy.ml", author = "alice@beehaw.org") })
    }

    @Test fun lemmyFeedLight() = shoot("lemmy-feed", dark = false, ::lemmySession) { it.reset(lemmyFeed(it)) }

    // A community you follow: the title bar shows the subscribed check.
    @Test fun lemmyCommunityLight() = shoot("lemmy-community", dark = false, ::lemmySession) { s ->
        s.reset(FeedScreen("linux@lemmy.ml", s.service.feedSorts.first(), s.service, kotlinx.coroutines.MainScope()).also {
            it.posts.addAll(posts.drop(3).map { p -> p.copy(name = "p" + p.id, community = "linux@lemmy.ml") })
        })
    }
    @Test fun threadDark() = shoot("thread", dark = true) { it.reset(thread(it)) }

    private fun discover(s: AccountSession) = s.discover().also { d ->
        d.deck.addAll(
            listOf(
                app.lurk.data.CommunityInfo(
                    "EarthPorn", "Amazing images of light and landscape",
                    "Pictures of the natural world. No people, no buildings, no filters.",
                    24_000_000, 3_100, null, null, over18 = false, subscribed = false,
                ),
                app.lurk.data.CommunityInfo("woodworking", null, "Sawdust and joinery.", 4_200_000, 900, null, null, false, false),
                app.lurk.data.CommunityInfo("tea", null, "Leaves, water, patience.", 600_000, 120, null, null, false, false),
            ),
        )
        d.peeks["EarthPorn"] = app.lurk.data.Peek.Loaded(posts.take(3))
    }

    @Test fun discoverLight() = shoot("discover", dark = false) { it.reset(discover(it)) }
    @Test fun discoverDark() = shoot("discover", dark = true) { it.reset(discover(it)) }

    // The account sheet's contents (the sheet itself draws in its own window, which isn't captured).
    private fun shootSheet(name: String, dark: Boolean, current: app.lurk.data.Account, syncProblem: String? = null, authNote: String? = null) {
        val others = listOf(
            app.lurk.data.Account("b", username = "night_owl", knownAs = "night_owl"),
            app.lurk.data.Account("c", knownAs = "old_throwaway"),
            app.lurk.data.Account("d", kind = app.lurk.data.ServiceKind.Lemmy, username = "lurker", knownAs = "lurker", instance = "lemmy.world"),
            app.lurk.data.Account("e", username = "secret", pinHash = "x", pinSalt = "y"),
        )
        val noop = {}
        val actions = AccountActions(noop, noop, {}, {}, noop, noop, noop, noop, {}, noop, noop, noop)
        compose.setContent {
            LurkTheme(darkOverride = dark) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(top = 24.dp)) {
                        AccountSheetContent(
                            current, listOf(current) + others, hidden = { it.id == "e" }, multiProfile = true,
                            syncing = false, syncProblem = syncProblem, authNote = authNote, actions = actions, pick = { it() },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name-${if (dark) "dark" else "light"}.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private val alice = app.lurk.data.Account(
        "a", username = "alice", knownAs = "alice", syncedAt = System.currentTimeMillis() - 5 * 60_000, showNsfw = true,
    )

    @Test fun accountsLight() = shootSheet("accounts", dark = false, alice)
    @Test fun accountsDark() = shootSheet("accounts", dark = true, alice)
    @Test fun accountsExpiredLight() = shootSheet(
        "accounts-expired", dark = false, alice.copy(username = null),
        authNote = "Reddit's sign-in cookie is gone. Sign-ins expire after a while; signing in again fixes it.",
    )
    @Test fun accountsProblemDark() = shootSheet(
        "accounts-problem", dark = true, alice,
        syncProblem = "Reddit is rate limiting. Wait a minute and try again.",
    )

    // An RSS account without its feed link: the sign-in card in place of Home.
    private fun rssSession(): AccountSession {
        val store = AccountStore(context, multiProfile = false)
        store.update(store.activeId) {
            it.copy(kind = app.lurk.data.ServiceKind.RedditRss, username = null, knownAs = null, token = null, communities = emptyList())
        }
        return AccountSession(context, store, store.activeId, Scripts(context), {}, {}, signInFirst = false)
    }
    @Test fun rssSignInLight() = shoot("rss-signin", dark = false, ::rssSession) {}
    @Test fun rssSignInDark() = shoot("rss-signin", dark = true, ::rssSession) {}

    @Test fun emptyFeedLight() = shoot("empty", dark = false) {
        it.reset(FeedScreen("tea", RedditSorts.feed.first(), it.service, kotlinx.coroutines.MainScope()))
    }

    // Reddit blocking Lurk's browser: the Chrome launcher in place of the feed.
    @Test fun redditChromeLight() {
        app.lurk.data.RedditAccess.init(context)
        app.lurk.data.RedditAccess.blocked()
        try {
            shoot("reddit-chrome", dark = false) {}
        } finally {
            app.lurk.data.RedditAccess.retry()
        }
    }

    // --- Layouts, budget, mutes ---

    private fun laidOut(s: AccountSession, layout: app.lurk.data.PostLayout) = feed(s, null).also { it.useLayout(layout) }

    @Test fun feedCompactLight() = shoot("feed-compact", dark = false) { it.reset(laidOut(it, app.lurk.data.PostLayout.Compact)) }
    @Test fun feedCompactDark() = shoot("feed-compact", dark = true) { it.reset(laidOut(it, app.lurk.data.PostLayout.Compact)) }
    @Test fun feedLargeLight() = shoot("feed-large", dark = false) { it.reset(feed(it, null, from = 1).also { f -> f.useLayout(app.lurk.data.PostLayout.Large) }) }
    @Test fun feedGalleryLight() = shoot("feed-gallery", dark = false) { it.reset(laidOut(it, app.lurk.data.PostLayout.Gallery)) }
    @Test fun feedGalleryDark() = shoot("feed-gallery", dark = true) { it.reset(laidOut(it, app.lurk.data.PostLayout.Gallery)) }

    @Test fun feedMutedLight() = shoot("feed-muted", dark = false) {
        it.reset(feed(it, null).also { f -> f.muted["t3_a3"] = "compose"; f.muted["t3_a5"] = "kotlin" })
    }
    @Test fun feedMutedShownLight() = shoot("feed-muted-shown", dark = false) {
        it.reset(feed(it, null).also { f -> f.muted["t3_a1"] = "weekly"; f.showMuted = true })
    }

    @Test fun budgetLight() = budget(dark = false)
    @Test fun budgetDark() = budget(dark = true)
    private fun budget(dark: Boolean) {
        app.lurk.data.ReadingSession.restart()
        app.lurk.data.ReadingSession.note(52)
        try {
            shoot("budget", dark) { s ->
                s.reset(FeedScreen(null, RedditSorts.feed.first(), s.service, kotlinx.coroutines.MainScope(), app.lurk.data.FeedHooks(budget = { 50 })).also {
                    it.posts.addAll(posts)
                })
            }
        } finally {
            app.lurk.data.ReadingSession.restart()
        }
    }

    // --- Read later ---

    @Test fun readLaterLight() = shoot("read-later", dark = false) { s ->
        s.readLater.clear()
        posts.take(3).reversed().forEach { s.readLater.add(it) }
        s.reset(app.lurk.data.ReadLaterScreen())
    }
    @Test fun readLaterEmptyDark() = shoot("read-later-empty", dark = true) { s ->
        s.readLater.clear()
        s.reset(app.lurk.data.ReadLaterScreen())
    }

    // --- Reader view ---

    private val article = app.lurk.data.Article(
        url = "https://www.example.org/2026/11/rivers-return",
        title = "The rivers that came back",
        byline = "Maya Okafor",
        site = "The Field Journal",
        published = "2026-11-02T09:00:00Z",
        words = 1840,
        blocks = listOf(
            app.lurk.data.ArticleBlock.Paragraph("When the last dam on the Elwha came down, few people expected salmon to return within a decade. They were back in <b>three years</b>."),
            app.lurk.data.ArticleBlock.Paragraph("Ecologists now call it the fastest recovery of its kind, and other towns <a href=\"https://example.org\">are paying attention</a>."),
            app.lurk.data.ArticleBlock.Heading(2, "What changed"),
            app.lurk.data.ArticleBlock.ListItem("Sediment that had piled up for a century washed down to the coast.", false, 1, 0),
            app.lurk.data.ArticleBlock.ListItem("Cold water reached spawning grounds again.", false, 2, 0),
            app.lurk.data.ArticleBlock.Quote("We didn't restore the river. We got out of its way."),
            app.lurk.data.ArticleBlock.Code("flow = basin.mean(axis=0)"),
            app.lurk.data.ArticleBlock.Paragraph("The next test is larger, and the stakes are higher."),
        ),
    )

    @Test fun readerLight() = shoot("reader", dark = false) { s ->
        s.reset(app.lurk.data.ReaderScreen(article.url, null, app.lurk.data.ArticleReader(context), kotlinx.coroutines.MainScope()).also { it.showForPreview(article) })
    }
    @Test fun readerDark() = shoot("reader", dark = true) { s ->
        s.reset(app.lurk.data.ReaderScreen(article.url, null, app.lurk.data.ArticleReader(context), kotlinx.coroutines.MainScope()).also { it.showForPreview(article) })
    }
    @Test fun readerLoadingLight() = shoot("reader-loading", dark = false) { s ->
        s.reset(app.lurk.data.ReaderScreen(article.url, "Sunrise over the Dolomites after a 4am hike", app.lurk.data.ArticleReader(context), kotlinx.coroutines.MainScope()))
    }

    // --- Settings ---

    @Test fun settingsLight() = shoot("settings", dark = false) { s ->
        s.stats.clear()
        val today = java.time.LocalDate.now()
        (0 until 7).forEach { back -> s.stats.recordRead(List(10 + back * 7 % 23) { "r/pics" }, today.minusDays(back.toLong())) }
        s.reset(s.settings())
    }
    @Test fun settingsDark() = shoot("settings", dark = true) { s -> s.reset(s.settings()) }
    @Test fun settingsLemmyLight() = shoot("settings-lemmy", dark = false, ::lemmySession) { s -> s.reset(s.settings()) }

    @Test fun mutesLight() = shoot("mutes", dark = false) { s ->
        s.setMutes(listOf("spoilers", "election", "/\\bep(isode)? ?\\d+/"))
        s.reset(s.settings(app.lurk.data.SettingsPage.Mutes))
    }
    @Test fun mutesEmptyDark() = shoot("mutes-empty", dark = true) { s ->
        s.setMutes(emptyList())
        s.reset(s.settings(app.lurk.data.SettingsPage.Mutes))
    }

    private fun sampleStats(s: AccountSession) {
        s.stats.clear()
        val today = java.time.LocalDate.now()
        val shape = listOf(12, 30, 18, 44, 26, 8, 0, 35, 52, 21, 17, 40, 63, 28, 14, 33, 47, 22, 9, 38, 55, 31, 26, 19, 42, 37, 12, 29, 48, 24)
        shape.forEachIndexed { back, n ->
            val day = today.minusDays(back.toLong())
            val names = listOf("r/pics", "r/androiddev", "r/Kotlin", "r/EarthPorn", "c/linux@lemmy.ml", "r/science")
            s.stats.recordRead(List(n) { names[(it * 7 + back) % names.size] }, day)
            s.stats.recordHidden(n / 3, n / 10, day)
            s.stats.recordTime(n * 40_000L, day)
            repeat(n / 6) { s.stats.recordThread(day) }
        }
    }

    @Test fun statsLight() = shoot("stats", dark = false) { s -> sampleStats(s); s.reset(s.settings(app.lurk.data.SettingsPage.Stats)) }
    @Test fun statsDark() = shoot("stats", dark = true) { s -> sampleStats(s); s.reset(s.settings(app.lurk.data.SettingsPage.Stats)) }

    @Test fun healthLight() = shoot("health", dark = false) { s ->
        val now = System.currentTimeMillis() / 1000
        fun p(c: String, ago: Long) = posts[0].copy(name = "h$c$ago", community = c, stickied = false, createdUtc = now - ago)
        s.activity.note("science", listOf(p("science", 80L * 24 * 3600)))
        s.activity.note(null, listOf(p("pics", 3600), p("androiddev", 7200), p("Kotlin", 2L * 24 * 3600)))
        s.reset(s.settings(app.lurk.data.SettingsPage.Health))
    }

    @Test fun mergedLight() = shoot("merged", dark = false, ::lemmySession) { s ->
        s.merge("linux", listOf("linux@lemmy.ml", "linux@lemmy.world", "linux@programming.dev"))
        s.reset(s.settings(app.lurk.data.SettingsPage.Merged))
    }

    // A merged community's feed, with its chip in the bottom bar.
    @Test fun mergedFeedLight() = shoot("merged-feed", dark = false, ::lemmySession) { s ->
        val key = s.merge("linux", listOf("linux@lemmy.ml", "linux@lemmy.world"))
        s.reset(FeedScreen(key, s.service.feedSorts.first(), s.service, kotlinx.coroutines.MainScope()).also {
            it.posts.addAll(posts.drop(2).mapIndexed { i, p -> p.copy(name = "m" + p.id, community = if (i % 2 == 0) "linux@lemmy.ml" else "linux@lemmy.world") })
        })
    }

    // Discover with a community's copies on other instances, and one joined on another account.
    private fun discoverCopies(s: AccountSession) = s.discover().also { d ->
        fun c(name: String, active: Int, members: Int) =
            app.lurk.data.CommunityInfo(name, null, "", members, active, null, null, over18 = false, subscribed = false)
        d.deck.addAll(
            listOf(
                app.lurk.data.CommunityInfo(
                    "selfhosted@lemmy.world", "Self-hosting", "Run your own services: home servers, homelabs and the software that makes them hum.",
                    48_000, 6_200, null, null, over18 = false, subscribed = false, posts = 9_400,
                    copies = listOf(c("selfhosted@lemmy.ml", 2_100, 19_000), c("selfhosted@programming.dev", 640, 5_400)),
                ),
                c("woodworking@lemmy.ml", 900, 4_200),
            ),
        )
        d.peeks["selfhosted@lemmy.world"] = app.lurk.data.Peek.Loaded(posts.take(2))
    }

    @Test fun discoverCopiesLight() = shoot("discover-copies", dark = false, ::lemmySession) { it.reset(discoverCopies(it)) }
    @Test fun discoverCopiesDark() = shoot("discover-copies", dark = true, ::lemmySession) { it.reset(discoverCopies(it)) }
}
