package app.lurk.web

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LinksTest {
    private fun u(s: String) = Uri.parse(s)

    @Test
    fun directVideoFiles() {
        assertEquals("https://i.imgur.com/abc.mp4" to true, Links.directVideo(u("https://i.imgur.com/abc.gifv")))
        assertEquals("https://example.com/clip.MP4" to false, Links.directVideo(u("https://example.com/clip.MP4")))
        assertNull(Links.directVideo(u("https://example.com/page.html")))
        assertNull(Links.directVideo(u("ftp://example.com/clip.mp4")))
    }

    @Test
    fun videoPages() {
        assertTrue(Links.isVideoPage(u("https://www.youtube.com/watch?v=abc")))
        assertTrue(Links.isVideoPage(u("https://youtu.be/abc")))
        assertTrue(Links.isVideoPage(u("https://clips.twitch.tv/SomeClip")))
        assertTrue(Links.isVideoPage(u("https://m.youtube.com/shorts/abc")))
        assertFalse(Links.isVideoPage(u("https://www.youtube.com/")))
        assertFalse(Links.isVideoPage(u("https://notyoutube.com/watch")))
        assertFalse(Links.isVideoPage(u("https://example.com/video")))
    }
}
