package app.lurk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditPagesTest {

    // Shaped like the 403 Reddit sends: ~190 KB of inline CSS before the message.
    private val networkSecurity = "<body class=theme-beta><div><style>.theme-light,:root{" +
        "--rem360:22.5rem;".repeat(11_500) + "}</style><script>var x = 'blocked';</script>" +
        "<h1>You&#39;ve been blocked by network security.</h1><p>If you think you&#39;ve been blocked by mistake, file a ticket below.</p></div></body>"

    @Test
    fun findsTheMessageAfterTheCss() {
        assertTrue(networkSecurity.length > 190_000)
        assertTrue(RedditPages.isBlock(networkSecurity))
        assertEquals(
            "You've been blocked by network security. If you think you've been blocked by mistake, file a ticket below.",
            RedditPages.text(networkSecurity),
        )
    }

    @Test
    fun oldBlockPage() {
        assertTrue(RedditPages.isBlock("<!doctype html><html><title>Blocked</title><body>whoa there, pardner!</body></html>"))
    }

    @Test
    fun dataIsNeverABlockPage() {
        // A comment quoting the phrase, inside JSON.
        val json = """{"data":{"body":"You've been blocked by network security lol"}}"""
        assertFalse(RedditPages.isBlock(json))
        assertEquals(null, RedditPages.text(json))
    }
}
