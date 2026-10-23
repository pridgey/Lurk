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
}
