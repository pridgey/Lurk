package app.lurk.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Topic tagging, and reading pages in the shape lemmyverse.net's community files have. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CommunityIndexTest {

    @Test
    fun topicsFromNamesAndTitles() {
        assertEquals(setOf("tech"), Topics.of("linux", "Linux", ""))
        // A word inside a longer name.
        assertEquals(setOf("tech", "gaming"), Topics.of("linux_gaming", "Linux Gaming", ""))
        assertTrue("memes" in Topics.of("programmerhumor", "Programmer Humor", ""))
        assertTrue("tabletop" in Topics.of("bg", "Board Games", ""))
    }

    @Test
    fun shortWordsOnlyCountWhole() {
        // "art" is a topic word, but not inside "smarthome".
        assertFalse("art" in Topics.of("smarthome", null, ""))
        assertTrue("art" in Topics.of("art", null, ""))
    }

    @Test
    fun descriptionsNeedTwoWords() {
        assertTrue(Topics.of("misc", null, "Share your news here.").isEmpty())
        assertEquals(setOf("science"), Topics.of("misc", null, "Physics, chemistry and everything between."))
    }

    @Test
    fun placesOnlyFromNames() {
        assertTrue(Topics.of("formula1", "Formula 1", "Races in Canada, Mexico and the USA.").none { it == "places" })
        assertTrue("places" in Topics.of("canada", "Canada", ""))
    }

    @Test
    fun readsAPage() {
        val list = CommunityIndex.parsePage(JSONArray(
            """
            [{"baseurl":"lemmy.dbzer0.com","url":"https://lemmy.dbzer0.com/c/hacking","name":"hacking","title":"Hacking",
              "desc":"A comm for **hacking**\n\nHardware and software tools","icon":"https://x/i.webp","nsfw":false,
              "counts":{"subscribers":175,"posts":25,"users_active_week":12,"users_active_month":21},
              "published":1763613791000,"isSuspicious":false,"score":618.8},
             {"baseurl":"spam.example","name":"buy-now","title":"Buy now","counts":{"subscribers":9000},"isSuspicious":true},
             {"baseurl":"lemmy.ml","name":"linux","title":"linux","desc":null,"counts":{"subscribers":68290,"posts":0}}]
            """.trimIndent(),
        ))
        assertEquals(2, list.size) // the suspicious one is left out
        val hacking = list[0]
        assertEquals("hacking", hacking.name)
        assertEquals("lemmy.dbzer0.com", hacking.host)
        assertEquals("Hacking", hacking.title)
        assertEquals("A comm for hacking Hardware and software tools", hacking.description)
        assertEquals(175, hacking.subscribers)
        assertEquals(25, hacking.posts)
        assertEquals(12, hacking.activeWeek)
        assertEquals(21, hacking.activeMonth)
        assertEquals(1763613791000L, hacking.published)
        val linux = list[1]
        assertNull(linux.title) // same as the name
        assertEquals("", linux.description)
        assertEquals(0, linux.posts)
        assertEquals(setOf("tech"), linux.topics)
    }
}
