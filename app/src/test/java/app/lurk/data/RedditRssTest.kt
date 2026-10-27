package app.lurk.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Parsing checks against hand-made entries in the shape Reddit's Atom feeds send. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RedditRssTest {

    private fun entry(id: String, sub: String, title: String, content: String, thumb: String? = null) = """
        <entry><author><name>/u/alice</name><uri>https://www.reddit.com/user/alice</uri></author>
        <category term="$sub" label="r/$sub"/><content type="html">$content</content><id>$id</id>
        ${thumb?.let { "<media:thumbnail url=\"$it\" />" } ?: ""}
        <link href="https://www.reddit.com/r/$sub/comments/${id.removePrefix("t3_")}/slug/" />
        <updated>2026-10-27T14:56:55+00:00</updated><published>2026-10-27T14:56:55+00:00</published>
        <title>$title</title></entry>
    """.trimIndent()

    /** The table Reddit wraps every entry's content in, escaped as it is in the feed. */
    private fun table(id: String, sub: String, link: String, extra: String = "") =
        "$extra&lt;table&gt; &lt;tr&gt;&lt;td&gt; submitted by &lt;a href=&quot;https://www.reddit.com/user/alice&quot;&gt; /u/alice &lt;/a&gt; &lt;br/&gt; " +
            "&lt;span&gt;&lt;a href=&quot;$link&quot;&gt;[link]&lt;/a&gt;&lt;/span&gt; " +
            "&lt;span&gt;&lt;a href=&quot;https://www.reddit.com/r/$sub/comments/${id.removePrefix("t3_")}/slug/&quot;&gt;[comments]&lt;/a&gt;&lt;/span&gt; &lt;/td&gt;&lt;/tr&gt;&lt;/table&gt;"

    private fun feed(vararg entries: String) =
        """<?xml version="1.0" encoding="UTF-8"?><feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">""" +
            """<category term="pics" label="r/pics"/><title>Front page</title>""" + entries.joinToString("") + "</feed>"

    @Test
    fun selfPostKeepsItsText() {
        val body = "&lt;!-- SC_OFF --&gt;&lt;div class=&quot;md&quot;&gt;&lt;p&gt;Hello &lt;strong&gt;there&lt;/strong&gt;&lt;/p&gt;&lt;/div&gt;&lt;!-- SC_ON --&gt; "
        val posts = RedditRss.parse(
            feed(entry("t3_aaa", "Kotlin", "A question", table("t3_aaa", "Kotlin", "https://www.reddit.com/r/Kotlin/comments/aaa/slug/", body))),
        )
        val p = posts.single()
        assertEquals("t3_aaa", p.name)
        assertEquals("A question", p.title)
        assertEquals("alice", p.author)
        assertEquals("Kotlin", p.community)
        assertEquals("/r/Kotlin/comments/aaa/slug/", p.threadRef)
        assertTrue(p.isSelf)
        assertEquals("Hello there", p.selftext)
        assertNull(p.media)
        assertFalse(p.hasStats)
        assertEquals(1_793_113_015L, p.createdUtc)
    }

    @Test
    fun imagePostShowsTheImage() {
        val thumb = "https://preview.redd.it/x.jpeg?width=640&amp;crop=smart&amp;s=1"
        val p = RedditRss.parse(
            feed(entry("t3_bbb", "pics", "A photo", table("t3_bbb", "pics", "https://i.redd.it/x.jpeg"), thumb)),
        ).single()
        assertFalse(p.isSelf)
        assertEquals("i.redd.it", p.domain)
        val image = p.media as PostMedia.Image
        // The preview is cropped, so the list shows the file itself.
        assertEquals("https://i.redd.it/x.jpeg", image.picture.url)
        assertEquals("https://i.redd.it/x.jpeg", image.picture.fullUrl)
    }

    @Test
    fun galleryIsALinkWithAThumbnail() {
        val p = RedditRss.parse(
            feed(
                entry(
                    "t3_ccc", "pics", "Some photos",
                    table("t3_ccc", "pics", "https://www.reddit.com/gallery/ccc"),
                    "https://preview.redd.it/y.png?width=140&amp;height=93",
                ),
            ),
        ).single()
        assertNull(p.media)
        assertEquals("https://www.reddit.com/gallery/ccc", p.url)
        assertEquals("https://preview.redd.it/y.png?width=140&height=93", p.thumbnail)
    }

    @Test
    fun redditVideoHasAPreviewAndNoStream() {
        val p = RedditRss.parse(
            feed(entry("t3_ddd", "videos", "A clip", table("t3_ddd", "videos", "https://v.redd.it/abc"), "https://preview.redd.it/z.jpg?width=640&amp;s=2")),
        ).single()
        val video = p.media as PostMedia.Video
        assertNull(video.stream)
        assertEquals("https://preview.redd.it/z.jpg?width=640&s=2", video.preview?.url)
    }

    @Test
    fun linkUrlsAreUnescaped() {
        val p = RedditRss.parse(
            feed(entry("t3_eee", "news", "An article", table("t3_eee", "news", "https://example.com/a?b=1&amp;amp;c=2"))),
        ).single()
        assertEquals("https://example.com/a?b=1&c=2", p.url)
        assertEquals("example.com", p.domain)
    }
}
