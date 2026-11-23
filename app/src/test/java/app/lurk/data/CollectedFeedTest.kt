package app.lurk.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectedFeedTest {

    private fun post(name: String, created: Long, title: String = name) = PostCodec.decode(
        JSONObject().put("name", name).put("title", title).put("createdUtc", created).put("community", "pics"),
    )

    @Test
    fun subredditsParseHoweverTheyreTyped() {
        assertEquals(
            listOf("pics", "Kotlin", "androiddev"),
            parseSubreddits("r/pics, /r/Kotlin/\nandroiddev PICS https://www.reddit.com/r/androiddev/ x"),
        )
    }

    @Test
    fun mergeKeepsFirstSeenAndTakesTheNewerCopy() {
        val old = listOf(CollectedPosts.Entry(post("t3_a", 10, "old"), 100))
        val merged = CollectedPosts.merge(old, listOf(post("t3_a", 10, "new"), post("t3_b", 20)), 200)
        assertEquals(listOf("t3_b", "t3_a"), merged.map { it.post.name })
        assertEquals("new", merged[1].post.title)
        assertEquals(100L, merged[1].firstSeen)
    }

    @Test
    fun mergeDropsPostsPastTheirTime() {
        val now = CollectedPosts.KEEP_MS + 1_000
        val old = listOf(CollectedPosts.Entry(post("t3_a", 10), 0))
        assertTrue(CollectedPosts.merge(old, emptyList(), now).isEmpty())
    }

    @Test
    fun pagingSurvivesNewPostsArriving() {
        val first = listOf(post("t3_d", 40), post("t3_c", 30), post("t3_b", 20), post("t3_a", 10))
        val (page, cursor) = CollectedPosts.pageAfter(first, null, 2)
        assertEquals(listOf("t3_d", "t3_c"), page.map { it.name })
        val (next, end) = CollectedPosts.pageAfter(listOf(post("t3_e", 50)) + first, cursor, 2)
        assertEquals(listOf("t3_b", "t3_a"), next.map { it.name })
        assertNull(end)
    }

    @Test
    fun busySubredditsDontShareWithQuietOnes() {
        val quiet = (1..12).map { "quiet$it" }
        val batches = LocalFeedSync.batches(quiet + "busy", mapOf("busy" to 60))
        assertEquals(listOf("busy"), batches.first())
        assertTrue(batches.all { it.size <= 10 })
        assertEquals(13, batches.sumOf { it.size })
    }

    @Test
    fun logCollapsesRepeatsAndKeepsTheNewest() {
        var log = CollectionLog.append(emptyList(), "skipped", 1)
        log = CollectionLog.append(log, "skipped", 2)
        assertEquals(listOf(CollectionLogEntry(2, "skipped", 2)), log)
        log = CollectionLog.append(log, "fetched", 3)
        assertEquals(2, log.size)
        repeat(CollectionLog.MAX_ENTRIES + 5) { log = CollectionLog.append(log, "line $it", 10L + it) }
        assertEquals(CollectionLog.MAX_ENTRIES, log.size)
        assertEquals("line ${CollectionLog.MAX_ENTRIES + 4}", log.last().message)
    }

    @Test
    fun backoffDoublesCapsAndHonorsRetryAfter() {
        val hour = 60 * 60_000L
        assertEquals(2 * hour, LocalFeedSync.backoffMs(1, null))
        assertEquals(4 * hour, LocalFeedSync.backoffMs(2, null))
        assertEquals(24 * hour, LocalFeedSync.backoffMs(9, null))
        assertEquals(5 * hour, LocalFeedSync.backoffMs(1, 5 * hour))
    }
}
