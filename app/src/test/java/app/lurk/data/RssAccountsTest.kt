package app.lurk.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reddit RSS accounts, and hiding standard Reddit accounts while that setting is off. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RssAccountsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val link = "https://www.reddit.com/.rss?feed=abc123&user=alice"

    @Before
    fun clear() {
        context.getSharedPreferences("accounts", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("reddit_rss", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun freshInstallStartsWithAnRssAccount() {
        val s = AccountStore(context, multiProfile = true)
        assertFalse(s.standardReddit)
        assertEquals(ServiceKind.RedditRss, s.find(s.activeId)!!.kind)
    }

    @Test
    fun standardRedditAccountsHideUntilTurnedOn() {
        val s = AccountStore(context, multiProfile = true)
        s.changeStandardReddit(true)
        val reddit = s.add(ServiceKind.Reddit) { it.copy(username = "bob", knownAs = "bob") }
        s.setActive(reddit.id)

        s.changeStandardReddit(false)
        assertTrue(s.visibleAccounts.none { it.kind == ServiceKind.Reddit })
        assertTrue(s.accounts.any { it.id == reddit.id }) // hidden, not removed
        assertEquals(ServiceKind.RedditRss, s.find(s.activeId)!!.kind)

        s.changeStandardReddit(true)
        assertTrue(s.visibleAccounts.any { it.id == reddit.id })
    }

    @Test
    fun aFeedLinkOnAStandardAccountBecomesAnRssAccount() {
        val s = AccountStore(context, multiProfile = true)
        s.changeStandardReddit(true)
        val reddit = s.add(ServiceKind.Reddit) { it.copy(username = "alice", knownAs = "alice") }
        s.setActive(reddit.id)
        s.changeStandardReddit(false)
        s.setActive(reddit.id) // as it was before this build: hidden accounts weren't a thing
        context.getSharedPreferences("reddit_rss", Context.MODE_PRIVATE).edit().putString("link:${reddit.id}", link).commit()

        val after = AccountStore(context, multiProfile = true)
        val rss = after.accounts.single { it.kind == ServiceKind.RedditRss && it.token == link }
        assertEquals("alice", rss.username)
        assertEquals(rss.id, after.activeId)

        // Only once.
        val again = AccountStore(context, multiProfile = true)
        assertEquals(1, again.accounts.count { it.token == link })
    }

    @Test
    fun linkNamesItsUser() {
        assertEquals("alice", RedditRssService.userOf(link))
    }
}
