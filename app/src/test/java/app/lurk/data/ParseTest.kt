package app.lurk.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing checks against hand-made JSON in the shape Reddit sends (with raw_json=1). */
class ParseTest {

    private fun postJson(extra: String = "") = """
        {"name":"t3_abc","id":"abc","title":"Hello","author":"op","subreddit":"pics",
         "score":42,"likes":null,"saved":false,"num_comments":3,"created_utc":1700000000.0,
         "permalink":"/r/pics/comments/abc/hello/","url":"https://i.redd.it/x.jpg",
         "domain":"i.redd.it","is_self":false,"selftext":"","selftext_html":null,
         "link_flair_text":null,"over_18":false,"spoiler":false,"stickied":false,"locked":false,
         "thumbnail":"https://b.thumbs.redditmedia.com/t.jpg" $extra}
    """

    private fun comment(id: String, parent: String, replies: String = "\"\"") = """
        {"kind":"t1","data":{"name":"t1_$id","id":"$id","author":"u_$id","body_html":"<div class=\"md\"><p>$id</p></div>",
         "score":1,"likes":true,"saved":false,"created_utc":1700000000,"parent_id":"$parent",
         "is_submitter":false,"stickied":false,"distinguished":null,"author_flair_text":null,
         "replies":$replies}}
    """

    private fun listing(vararg children: String) = """{"kind":"Listing","data":{"children":[${children.joinToString(",")}]}}"""

    @Test
    fun imagePostUsesMidSizePreview() {
        val post = Parse.post(JSONObject(postJson(""",
            "post_hint":"image",
            "preview":{"images":[{"source":{"url":"https://preview.redd.it/full.jpg","width":2000,"height":1000},
              "resolutions":[{"url":"https://preview.redd.it/320.jpg","width":320,"height":160},
                             {"url":"https://preview.redd.it/640.jpg","width":640,"height":320}]}]}""")))
        val media = post.media as PostMedia.Image
        assertEquals("https://preview.redd.it/640.jpg", media.picture.url)
        assertEquals("https://preview.redd.it/full.jpg", media.picture.fullUrl)
        assertEquals(2f, media.picture.aspect)
        assertNull(post.thumbnail) // big media replaces the thumbnail
        assertNull(post.likes)
    }

    @Test
    fun galleryKeepsOrderAndSkipsInvalid() {
        val post = Parse.post(JSONObject(postJson(""",
            "is_gallery":true,
            "gallery_data":{"items":[{"media_id":"b"},{"media_id":"bad"},{"media_id":"a"}]},
            "media_metadata":{
              "a":{"status":"valid","s":{"u":"https://i.redd.it/a.jpg","x":100,"y":100},"p":[]},
              "b":{"status":"valid","s":{"u":"https://i.redd.it/b.jpg","x":800,"y":400},
                   "p":[{"u":"https://preview.redd.it/b640.jpg","x":640,"y":320}]},
              "bad":{"status":"failed"}}""")))
        val pictures = (post.media as PostMedia.Gallery).pictures
        assertEquals(listOf("https://preview.redd.it/b640.jpg", "https://i.redd.it/a.jpg"), pictures.map { it.url })
    }

    @Test
    fun redditVideoHasStreams() {
        val post = Parse.post(JSONObject(postJson(""",
            "is_video":true,"post_hint":"hosted:video",
            "secure_media":{"reddit_video":{"hls_url":"https://v.redd.it/v/HLSPlaylist.m3u8?a=1",
              "fallback_url":"https://v.redd.it/v/DASH_720.mp4?source=fallback",
              "width":1280,"height":720,"duration":42,"is_gif":false}},
            "preview":{"images":[{"source":{"url":"https://preview.redd.it/v.jpg","width":1280,"height":720},"resolutions":[]}]}""")))
        val video = post.media as PostMedia.Video
        assertEquals("https://v.redd.it/v/HLSPlaylist.m3u8?a=1", video.stream?.hlsUrl)
        assertEquals("https://v.redd.it/v/DASH_720.mp4?source=fallback", video.stream?.mp4Url)
        assertEquals(42, video.stream?.durationSec)
        assertEquals("https://preview.redd.it/v.jpg", video.preview?.url)
    }

    @Test
    fun crosspostedVideoComesFromParent() {
        val post = Parse.post(JSONObject(postJson(""",
            "crosspost_parent_list":[{"is_video":true,"secure_media":{"reddit_video":
              {"hls_url":"https://v.redd.it/p/HLSPlaylist.m3u8","fallback_url":null,"width":10,"height":10,"duration":3,"is_gif":true}}}]""")))
        val stream = (post.media as PostMedia.Video).stream!!
        assertEquals("https://v.redd.it/p/HLSPlaylist.m3u8", stream.hlsUrl)
        assertNull(stream.mp4Url)
        assertTrue(stream.isGif)
    }

    @Test
    fun gifvLinkUsesRedditsConversion() {
        val post = Parse.post(JSONObject(postJson(""",
            "post_hint":"link",
            "preview":{"images":[],"reddit_video_preview":{"fallback_url":"https://v.redd.it/g/DASH_480.mp4",
              "hls_url":"https://v.redd.it/g/HLSPlaylist.m3u8","width":480,"height":480,"duration":5,"is_gif":true}}""")))
        assertTrue((post.media as PostMedia.Video).stream!!.isGif)
    }

    @Test
    fun youtubeHasEmbedButNoStream() {
        val post = Parse.post(JSONObject(postJson(""",
            "post_hint":"rich:video",
            "secure_media_embed":{"content":"<iframe width=\"356\" height=\"200\" src=\"https://www.youtube.com/embed/abc?feature=oembed\"></iframe>",
              "width":356,"height":200},
            "secure_media":{"type":"youtube.com","oembed":{"provider_name":"YouTube",
              "thumbnail_url":"https://i.ytimg.com/vi/abc/hqdefault.jpg","thumbnail_width":480,"thumbnail_height":360}}""")))
        val video = post.media as PostMedia.Video
        assertNull(video.stream)
        assertEquals("YouTube", video.embed?.provider)
        assertTrue(video.embed!!.html.contains("youtube.com/embed/abc"))
        assertEquals(356, video.embed!!.width)
        assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", video.preview?.url) // no Reddit preview: oEmbed thumbnail
    }

    @Test
    fun richVideoWithoutEmbedHasNeither() {
        val video = Parse.post(JSONObject(postJson(""","post_hint":"rich:video""""))).media as PostMedia.Video
        assertNull(video.stream)
        assertNull(video.embed)
    }

    @Test
    fun linkPostKeepsThumbnail() {
        val post = Parse.post(JSONObject(postJson(""","post_hint":"link"""")))
        assertNull(post.media)
        assertEquals("https://b.thumbs.redditmedia.com/t.jpg", post.thumbnail)
    }

    @Test
    fun feedReadsAfterAndSkipsNonPosts() {
        val page = Parse.feed(JSONObject("""{"kind":"Listing","data":{"after":"t3_next","children":[
            {"kind":"t3","data":${postJson()}},{"kind":"t5","data":{}}]}}"""))
        assertEquals(1, page.posts.size)
        assertEquals("t3_next", page.after)
        assertNull(Parse.feed(JSONObject("""{"data":{"after":null,"children":[]}}""")).after)
    }

    @Test
    fun threadFlattensTreeWithDepths() {
        val tree = JSONArray("""[
            ${listing("""{"kind":"t3","data":${postJson()}}""")},
            ${listing(
                comment("a", "t3_abc", listing(
                    comment("b", "t1_a", listing(
                        """{"kind":"more","data":{"name":"t1__","id":"_","parent_id":"t1_b","count":0,"children":[]}}""",
                    )),
                )),
                comment("c", "t3_abc"),
                """{"kind":"more","data":{"name":"t1_m","id":"m","parent_id":"t3_abc","count":5,"children":["d","e"]}}""",
            )}
        ]""")
        val data = Parse.thread(tree, "/r/pics/comments/abc/hello")
        assertEquals(listOf("t1_a" to 0, "t1_b" to 1, "more" to 2, "t1_c" to 0, "more" to 0),
            data.items.map { (if (it is Comment) it.name else "more") to it.depth })
        assertTrue((data.items[2] as More).children.isEmpty()) // continue-this-thread
        assertEquals(false, data.isSingleThread)
        assertTrue(Parse.thread(tree, "/r/pics/comments/abc/hello/a").isSingleThread)
    }

    @Test
    fun moreChildrenNestUnderParent() {
        val more = More("t1_m", listOf("d", "e", "f"), 3, depth = 2, parentName = "t1_b")
        val root = JSONObject("""{"json":{"errors":[],"data":{"things":[
            ${comment("d", "t1_b")}, ${comment("f", "t1_d")}, ${comment("e", "t1_b")}]}}}""")
        val items = Parse.moreChildren(root, more, op = "op")
        assertEquals(listOf("t1_d" to 2, "t1_f" to 3, "t1_e" to 2),
            items.map { (it as Comment).name to it.depth })
    }
}
