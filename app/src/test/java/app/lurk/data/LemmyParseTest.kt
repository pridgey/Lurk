package app.lurk.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Parsing checks against hand-made JSON in the shape Lemmy 0.19's API v3 sends. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LemmyParseTest {

    private fun postView(postExtra: String = "", viewExtra: String = "", communityLocal: Boolean = true) = JSONObject(
        """
        {"post":{"id":42,"name":"Hello Lemmy","creator_id":7,"community_id":3,"published":"2024-05-01T12:00:00.000000Z",
                 "ap_id":"https://lemmy.world/post/42","nsfw":false,"locked":false,"featured_community":false,"featured_local":false
                 $postExtra},
         "creator":{"id":7,"name":"alice","local":false,"actor_id":"https://beehaw.org/u/alice"},
         "community":{"id":3,"name":"technology","local":$communityLocal,"actor_id":"https://lemmy.ml/c/technology","nsfw":false},
         "counts":{"score":120,"comments":14},
         "saved":false,"my_vote":1
         $viewExtra}
        """.trimIndent(),
    )

    @Test
    fun textPost() {
        val post = LemmyParse.post(postView(""","body":"Some **bold** text""""))
        assertEquals("p42", post.name)
        assertEquals("post:42", post.threadRef)
        assertEquals("Hello Lemmy", post.title)
        assertEquals("alice@beehaw.org", post.author)
        assertEquals("technology", post.community)
        assertEquals(120, post.score)
        assertEquals(true, post.likes)
        assertEquals(14, post.numComments)
        assertTrue(post.isSelf)
        assertTrue(post.selftextHtml!!.contains("<strong>bold</strong>"))
        assertEquals(1714564800L, post.createdUtc)
        assertEquals("https://lemmy.world/post/42", post.shareUrl)
        assertNull(post.media)
    }

    @Test
    fun remoteCommunityKeepsItsInstance() {
        assertEquals("technology@lemmy.ml", LemmyParse.post(postView(communityLocal = false)).community)
    }

    @Test
    fun imagePost() {
        val post = LemmyParse.post(postView(
            ""","url":"https://lemmy.world/pictrs/image/abc.jpeg","url_content_type":"image/jpeg"""",
            ""","image_details":{"link":"https://lemmy.world/pictrs/image/abc.jpeg","width":800,"height":600}""",
        ))
        val image = post.media as PostMedia.Image
        assertEquals("https://lemmy.world/pictrs/image/abc.jpeg", image.picture.fullUrl)
        assertEquals(800, image.picture.width)
        assertFalse(post.isSelf)
    }

    @Test
    fun videoFilePost() {
        val post = LemmyParse.post(postView(""","url":"https://files.example/clip.mp4","thumbnail_url":"https://x/t.jpg""""))
        val video = post.media as PostMedia.Video
        assertEquals("https://files.example/clip.mp4", video.stream!!.mp4Url)
        assertEquals("https://x/t.jpg", video.preview!!.url)
    }

    @Test
    fun linkPostUsesThumbnail() {
        val post = LemmyParse.post(postView(""","url":"https://example.com/article","thumbnail_url":"https://x/t.jpg""""))
        assertNull(post.media)
        assertEquals("https://x/t.jpg", post.thumbnail)
        assertEquals("example.com", post.domain)
    }

    @Test
    fun downvoteAndNoVote() {
        assertEquals(false, LemmyParse.post(postView(viewExtra = ""","my_vote":-1""")).likes)
        assertNull(LemmyParse.post(postView(viewExtra = ""","my_vote":0""")).likes)
    }

    private fun commentView(id: Int, path: String, content: String = "c$id", children: Int = 0, author: String = "bob") = """
        {"comment":{"id":$id,"post_id":42,"content":"$content","path":"$path","published":"2024-05-01T13:00:00Z",
                    "removed":false,"deleted":false,"distinguished":false},
         "creator":{"id":1,"name":"$author","local":true},
         "counts":{"score":5,"child_count":$children},
         "creator_is_admin":false,"saved":false}
    """.trimIndent()

    @Test
    fun flatCommentsBecomeATree() {
        val list = JSONArray("[" + listOf(
            commentView(1, "0.1", children = 2),
            commentView(2, "0.1.2", children = 1),
            commentView(4, "0.4", author = "alice@beehaw.org"),
            commentView(3, "0.1.2.3"),
        ).joinToString(",") + "]")
        val items = LemmyParse.tree(list, "p42", "p42", 0, op = "alice@beehaw.org")
        val shape = items.map { (it as Comment).name to it.depth }
        assertEquals(listOf("c1" to 0, "c2" to 1, "c3" to 2, "c4" to 0), shape)
        assertEquals("c2", (items[2] as Comment).parentName)
        assertTrue((items[3] as Comment).isOp)
        assertTrue((items[0] as Comment).bodyHtml.contains("c1"))
    }

    @Test
    fun repliesPastTheDepthLimitLoadInPlace() {
        val list = JSONArray("[" + commentView(1, "0.1", children = 3) + "]")
        val items = LemmyParse.tree(list, "p42", "p42", 0, op = "x")
        val more = items[1] as More
        assertEquals(listOf("1"), more.children)
        assertEquals(3, more.count)
        assertEquals(1, more.depth)
        assertEquals("c1", more.parentName)
    }

    @Test
    fun subtreeStartsUnderItsParent() {
        // Loading comment 1's replies: the list includes comment 1 itself, which is skipped.
        val list = JSONArray("[" + listOf(commentView(1, "0.1", children = 1), commentView(2, "0.1.2")).joinToString(",") + "]")
        val items = LemmyParse.tree(list, "p42", "c1", 3, op = "x")
        assertEquals(listOf("c2" to 3), items.map { (it as Comment).name to it.depth })
    }

    @Test
    fun commentImagesShowInPlace() {
        val list = JSONArray("[" + commentView(1, "0.1", content = "look ![](https://lemmy.world/pictrs/image/a.png)") + "]")
        val comment = LemmyParse.tree(list, "p42", "p42", 0, op = "x").single() as Comment
        assertEquals(listOf("https://lemmy.world/pictrs/image/a.png"), comment.images.map { it.url })
    }

    @Test
    fun timestampsWithoutAZone() {
        assertEquals(1714564800L, LemmyParse.time("2024-05-01T12:00:00.000000"))
        assertEquals(0L, LemmyParse.time(null))
    }

    @Test
    fun communityForDiscover() {
        val info = LemmyParse.communityInfo(JSONObject(
            """
            {"community":{"id":3,"name":"linux","title":"Linux","local":false,"actor_id":"https://lemmy.ml/c/linux",
                          "description":"# Welcome\n\nAll things **Linux**. See [the rules](https://lemmy.ml/rules).\n\n![banner](https://x/b.png)",
                          "icon":"https://lemmy.ml/pictrs/image/icon.png","nsfw":false},
             "counts":{"subscribers":52000,"users_active_week":3400},
             "subscribed":"NotSubscribed"}
            """.trimIndent(),
        ))
        assertEquals("linux@lemmy.ml", info.name)
        assertNull(info.title) // same as the name
        assertEquals("Welcome All things Linux. See the rules.", info.description)
        assertEquals(52000, info.subscribers)
        assertEquals(3400, info.activeUsers)
        assertEquals("https://lemmy.ml/pictrs/image/icon.png", info.icon)
        assertNull(info.banner)
        assertFalse(info.subscribed)
    }

    @Test
    fun plainTextFromMarkdown() {
        assertEquals("A list: one two", Markdown.plain("A list:\n\n- one\n* two\n\n---"))
        assertEquals("snake_case stays, emphasis goes", Markdown.plain("snake_case stays, *emphasis* _goes_"))
        assertEquals("", Markdown.plain("![only an image](https://x/y.png)"))
    }
}
