package app.lurk.web

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lurk.data.LemmyNaming
import app.lurk.data.LemmyService
import app.lurk.data.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LemmyLinksTest {
    private val links = LemmyLinks("lemmy.world")
    private fun u(s: String) = Uri.parse(s)

    @Test
    fun localLinksOpenNatively() {
        assertEquals(Route.Feed(null), links.route(u("https://lemmy.world/")))
        assertEquals(Route.Thread("post:123"), links.route(u("https://lemmy.world/post/123")))
        assertEquals(Route.Thread("comment:9"), links.route(u("https://lemmy.world/comment/9")))
        assertEquals(Route.Thread("comment:9"), links.route(u("https://lemmy.world/post/123/9")))
        assertEquals(Route.Feed("technology"), links.route(u("https://lemmy.world/c/technology")))
        assertEquals(Route.Feed("linux@lemmy.ml"), links.route(u("https://lemmy.world/c/linux@lemmy.ml")))
        assertNull(links.route(u("https://lemmy.world/u/alice")))
    }

    @Test
    fun otherInstancesAreLookedUp() {
        assertEquals(Route.Thread("https://beehaw.org/post/5"), links.route(u("https://beehaw.org/post/5")))
        assertEquals(Route.Feed("linux@lemmy.ml"), links.route(u("https://lemmy.ml/c/linux")))
        assertNull(links.route(u("https://example.com/about")))
    }

    @Test
    fun webPages() {
        assertEquals("https://lemmy.world/post/1", links.thread("post:1"))
        assertEquals("https://lemmy.world/comment/2", links.thread("comment:2"))
        assertEquals("https://lemmy.world/c/linux@lemmy.ml", links.feed("linux@lemmy.ml", null))
        assertEquals("https://lemmy.world/u/bob", links.resolve("/u/bob").toString())
        assertEquals(Decision.Stay, links.decide(u("https://lemmy.world/inbox")))
        assertEquals(Decision.External, links.decide(u("https://example.com/")))
        assertEquals("linux", links.locate("https://lemmy.world/c/Linux").community?.lowercase())
    }

    @Test
    fun instanceNames() {
        assertEquals("lemmy.world", LemmyService.normalizeInstance(" https://Lemmy.World/c/x "))
        assertEquals("beehaw.org", LemmyService.normalizeInstance("beehaw.org"))
    }

    @Test
    fun naming() {
        val naming = LemmyNaming("lemmy.world")
        assertEquals("c/linux@lemmy.ml", naming.community("linux@lemmy.ml"))
        assertEquals("@bob@lemmy.world", naming.user("bob"))
        assertEquals("@bob@beehaw.org", naming.user("bob@beehaw.org"))
        assertEquals("linux", naming.chip("linux@lemmy.ml", listOf("linux@lemmy.ml", "pics")))
        assertEquals("linux@lemmy.ml", naming.chip("linux@lemmy.ml", listOf("linux@lemmy.ml", "linux")))
        assertEquals("linux@lemmy.ml", naming.clean("!linux@lemmy.ml"))
        assertTrue(naming.isCommunityName("linux@lemmy.ml"))
        assertFalse(naming.isCommunityName("not a name"))
    }

    @Test
    fun markdownExtras() {
        val spoiler = Markdown.toHtml("::: spoiler Ending\nThey all lived.\n:::")
        assertTrue(spoiler, spoiler.contains("<blockquote>") && spoiler.contains("Spoiler: Ending") && spoiler.contains("They all lived."))
        val mention = Markdown.toHtml("See !linux@lemmy.ml for more")
        assertTrue(mention, mention.contains("""<a href="https://lemmy.ml/c/linux">!linux@lemmy.ml</a>"""))
        assertTrue(Markdown.toHtml("<script>x</script>").contains("&lt;script&gt;"))
        assertTrue(Markdown.toHtml("go to https://example.com now").contains("<a href=\"https://example.com\">"))
    }
}
