package app.lurk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingFeaturesTest {

    private fun post(
        name: String, title: String = "A post", selftext: String = "", url: String = "https://example.com/$name",
        isSelf: Boolean = false, createdUtc: Long = 0, score: Int = 0, comments: Int = 0, author: String = "someone",
        flair: String? = null,
    ) = Post(
        name = name, id = name, title = title, author = author, community = "c", score = score, likes = null,
        saved = false, numComments = comments, createdUtc = createdUtc, threadRef = name, url = url,
        domain = "example.com", isSelf = isSelf, selftext = selftext, selftextHtml = null, flair = flair,
        over18 = false, spoiler = false, stickied = false, locked = false, media = null, thumbnail = null,
    )

    // --- Link cleaning ---

    @Test fun cleanerDropsCampaignTagsAndKeepsTheRest() {
        assertEquals(
            "https://example.com/a?id=7#top",
            LinkCleaner.clean("https://example.com/a?utm_source=x&id=7&utm_medium=y&fbclid=abc#top"),
        )
    }

    @Test fun cleanerDropsTheQueryWhenNothingIsLeft() {
        assertEquals("https://example.com/a", LinkCleaner.clean("https://example.com/a?utm_campaign=z&gclid=1"))
    }

    @Test fun cleanerLeavesCleanLinksAlone() {
        val url = "https://example.com/search?q=utm&page=2"
        assertEquals(url, LinkCleaner.clean(url))
    }

    @Test fun cleanerKnowsSiteTrackers() {
        assertEquals("https://youtu.be/abc", LinkCleaner.clean("https://youtu.be/abc?si=XyZ"))
        assertEquals("https://www.youtube.com/watch?v=abc&t=30", LinkCleaner.clean("https://www.youtube.com/watch?v=abc&si=1&t=30"))
        // "si" means something else elsewhere.
        assertEquals("https://example.com/?si=1", LinkCleaner.clean("https://example.com/?si=1"))
        assertEquals("https://www.amazon.co.uk/dp/B0", LinkCleaner.clean("https://www.amazon.co.uk/dp/B0?ref=abc&pd_rd_r=1"))
    }

    @Test fun cleanerKeepsFunctionalRedditParams() {
        val url = "https://www.reddit.com/r/x/comments/1/y/z?context=3"
        assertEquals(url, LinkCleaner.clean("$url&share_id=abc"))
        assertEquals(url, LinkCleaner.clean(url))
    }

    // --- Muted words ---

    @Test fun mutesMatchWholeWordsAnyCase() {
        val f = MuteFilter(listOf("cat"))
        assertEquals("cat", f.match(post("1", title = "My CAT did a thing")))
        assertNull(f.match(post("2", title = "A category of things")))
        assertNull(f.match(post("3", title = "Concatenate strings")))
    }

    @Test fun mutesMatchPhrasesTextAndFlair() {
        val f = MuteFilter(listOf("season finale", "spoilers"))
        assertEquals("season finale", f.match(post("1", title = "x", selftext = "That Season Finale though")))
        assertEquals("spoilers", f.match(post("2", title = "x", flair = "Spoilers")))
    }

    @Test fun mutesTakeExpressions() {
        val f = MuteFilter(listOf("/\\bep(isode)? ?\\d+/"))
        assertTrue(f.match(post("1", title = "Thoughts on Episode 4?")) != null)
        assertTrue(f.match(post("2", title = "ep12 discussion")) != null)
        assertNull(f.match(post("3", title = "Epic win")))
    }

    @Test fun badExpressionsAreRejectedNotFatal() {
        assertFalse(MuteFilter.isValid("/(unclosed/"))
        assertTrue(MuteFilter.isValid("c++"))
        assertEquals("c++", MuteFilter(listOf("/(unclosed/", "c++")).match(post("1", title = "Learning C++ today")))
    }

    // --- Merged feeds ---

    @Test fun mergeOrdersNewByTime() {
        val a = listOf(post("a1", createdUtc = 50), post("a2", createdUtc = 10))
        val b = listOf(post("b1", createdUtc = 40), post("b2", createdUtc = 30))
        assertEquals(listOf("a1", "b1", "b2", "a2"), MergedFeed.merge(listOf(a, b), "New").map { it.name })
    }

    @Test fun mergeOrdersTopByScoreAndTakesTurnsOtherwise() {
        val a = listOf(post("a1", score = 5), post("a2", score = 1))
        val b = listOf(post("b1", score = 9), post("b2", score = 3))
        assertEquals(listOf("b1", "a1", "b2", "a2"), MergedFeed.merge(listOf(a, b), "TopWeek").map { it.name })
        assertEquals(listOf("a1", "b1", "a2", "b2"), MergedFeed.merge(listOf(a, b), "Hot").map { it.name })
    }

    @Test fun mergeDropsTheSameLinkFromAnotherCopy() {
        val a = listOf(post("a1", url = "https://news.example/story?utm_source=lemmy"))
        val b = listOf(post("b1", url = "http://www.news.example/story/"), post("b2", url = "https://other.example/x"))
        assertEquals(listOf("a1", "b2"), MergedFeed.merge(listOf(a, b), "Hot").map { it.name })
    }

    @Test fun mergeDropsTheSameTextPostByTheSamePerson() {
        val a = listOf(post("a1", title = "Weekly thread", isSelf = true, author = "mod@lemmy.ml"))
        val b = listOf(post("b1", title = "weekly thread ", isSelf = true, author = "mod"))
        assertEquals(listOf("a1"), MergedFeed.merge(listOf(a, b), "New").map { it.name })
    }

    // --- Feed budget ---

    @Test fun budgetSpendsAndKeepsGoing() {
        ReadingSession.restart()
        assertFalse(ReadingSession.spent(0))
        ReadingSession.note(19)
        assertFalse(ReadingSession.spent(20))
        ReadingSession.note(1)
        assertTrue(ReadingSession.spent(20))
        ReadingSession.keepGoing()
        assertFalse(ReadingSession.spent(20))
        ReadingSession.note(20)
        assertTrue(ReadingSession.spent(20))
        ReadingSession.restart()
    }

    @Test fun aLongBreakStartsANewSitting() {
        ReadingSession.restart()
        ReadingSession.note(30)
        ReadingSession.onAway(now = 1_000L)
        ReadingSession.onBack(now = 1_000L + 60_000L)
        assertEquals(30, ReadingSession.read)
        ReadingSession.onAway(now = 2_000_000L)
        ReadingSession.onBack(now = 2_000_000L + ReadingSession.BREAK_MS)
        assertEquals(0, ReadingSession.read)
    }

    // --- Reader view ---

    @Test fun articlesAreTellable() {
        assertTrue(ReaderScreen.looksLikeArticle("https://www.theguardian.com/world/2026/nov/01/some-story"))
        assertFalse(ReaderScreen.looksLikeArticle("https://www.youtube.com/watch?v=abc"))
        assertFalse(ReaderScreen.looksLikeArticle("https://i.imgur.com/abc.jpg"))
        assertFalse(ReaderScreen.looksLikeArticle("https://example.com/"))
        assertFalse(ReaderScreen.looksLikeArticle("mailto:me@example.com"))
        assertFalse(ReaderScreen.looksLikeArticle("https://example.com/report.pdf"))
    }

    // --- Community health ---

    @Test fun healthNeedsTheCommunitysOwnFeedToCallItQuiet() {
        val now = 100L * 24 * 3600
        val old = now - 60L * 24 * 3600
        assertEquals(Health.Unknown, CommunityActivity.health(null, now))
        assertEquals(Health.Active, CommunityActivity.health(Activity(now - 3600, 0, ownFeed = false), now))
        assertEquals(Health.Unknown, CommunityActivity.health(Activity(old, 0, ownFeed = false), now))
        assertEquals(Health.Quiet, CommunityActivity.health(Activity(old, 0, ownFeed = true), now))
        assertEquals(Health.Quiet, CommunityActivity.health(Activity(0, 0, ownFeed = true), now))
    }

    // --- Read later ---

    @Test fun postsSurviveTheTripToJsonAndBack() {
        val p = post("t3_x", title = "Hello", selftext = "Body", flair = "News").copy(
            media = PostMedia.Gallery(listOf(Picture("u1", "f1", 100, 50), Picture("u2", "f2", 10, 10))),
            likes = true,
            thumbnail = "thumb",
            bodyImages = listOf(InlineImage("giphy|abc", "u", "f", 1, 2)),
        )
        assertEquals(p, PostCodec.decode(PostCodec.encode(p)))
        val v = post("v").copy(media = PostMedia.Video(null, VideoStream("h", null, 1, 2, 3, isGif = true, hasAudio = null), VideoEmbed("<iframe>", 4, 5, "YouTube")))
        assertEquals(v, PostCodec.decode(PostCodec.encode(v)))
    }
}
