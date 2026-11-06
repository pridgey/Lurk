package app.lurk.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Slotting subreddit posts into Home. */
class InterleaveTest {

    private fun post(id: String) = Post(
        name = id, id = id, title = id, author = "a", community = "c", score = 0, likes = null,
        saved = false, numComments = 0, createdUtc = 0, threadRef = "/r/c/comments/$id/", url = "",
        domain = "self.c", isSelf = true, selftext = "", selftextHtml = null, flair = null, over18 = false,
        spoiler = false, stickied = false, locked = false, media = null, thumbnail = null,
    )

    private fun names(posts: List<Post>) = posts.joinToString(" ") { it.name }
    private val base = (1..8).map { post("b$it") }

    @Test
    fun spreadsExtrasFromStart() {
        val out = interleave(base, listOf(post("x1"), post("x2")), start = 2, every = 3)
        assertEquals("b1 b2 x1 b3 b4 b5 x2 b6 b7 b8", names(out))
    }

    @Test
    fun leftoversGoOnTheEnd() {
        val out = interleave(base, listOf(post("x1"), post("x2")), start = 7, every = 4)
        assertEquals("b1 b2 b3 b4 b5 b6 b7 x1 b8 x2", names(out))
    }

    @Test
    fun skipsPostsAlreadyThere() {
        val out = interleave(base, listOf(post("b3"), post("x1")), start = 0, every = 2)
        assertEquals("x1 b1 b2 b3 b4 b5 b6 b7 b8", names(out))
    }
}
