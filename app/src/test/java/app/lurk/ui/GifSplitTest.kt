package app.lurk.ui

import app.lurk.data.InlineGif
import org.junit.Assert.assertEquals
import org.junit.Test

class GifSplitTest {

    private val giphy = InlineGif("giphy|AbC123|downsized", "https://external-preview.redd.it/g.gif", 200, 100)
    private val upload = InlineGif("upl0ad", "https://i.redd.it/upl0ad.gif", 10, 10)

    @Test
    fun gifReplacesItsLinkInPlace() {
        val html = """<div class="md"><p>before</p><p><a href="https://giphy.com/gifs/AbC123">https://giphy.com/gifs/AbC123</a></p><p>after</p></div>"""
        val parts = splitGifs(html, listOf(giphy))
        assertEquals(3, parts.size)
        assertEquals(BodyPart.Gif(giphy.url, 200, 100), parts[1])
        assert((parts[0] as BodyPart.Html).html.contains("before"))
        assert((parts[2] as BodyPart.Html).html.contains("after"))
    }

    @Test
    fun uploadMatchesPreviewLinkAndGifOnlyBodyHasNoText() {
        val html = """<div class="md"><p><a href="https://preview.redd.it/upl0ad.gif?width=10&amp;format=mp4&amp;s=x">gif</a></p></div>"""
        assertEquals(listOf(BodyPart.Gif(upload.url, 10, 10)), splitGifs(html, listOf(upload)))
    }

    @Test
    fun directGifLinksInlineButOtherLinksStay() {
        val html = """<p>see <a href="https://i.imgur.com/cat.gif">this</a> and <a href="https://example.com/a.gif">that</a></p>"""
        val parts = splitGifs(html, emptyList())
        assertEquals(BodyPart.Gif("https://i.imgur.com/cat.gif", 0, 0), parts[1])
        assert((parts[2] as BodyPart.Html).html.contains("example.com"))
    }

    @Test
    fun unlinkedGifGoesAtTheEndAndPlainBodyIsUntouched() {
        assertEquals(listOf(BodyPart.Html("<p>hi</p>"), BodyPart.Gif(upload.url, 10, 10)), splitGifs("<p>hi</p>", listOf(upload)))
        assertEquals(listOf(BodyPart.Html("<p>hi</p>")), splitGifs("<p>hi</p>", emptyList()))
    }
}
