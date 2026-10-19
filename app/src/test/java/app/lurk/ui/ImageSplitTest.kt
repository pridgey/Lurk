package app.lurk.ui

import app.lurk.data.InlineImage
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSplitTest {

    private val giphy = InlineImage("giphy|AbC123|downsized", "https://external-preview.redd.it/g.gif", "https://external-preview.redd.it/g.gif", 200, 100)
    private val upload = InlineImage("upl0ad", "https://i.redd.it/upl0ad.gif", "https://i.redd.it/upl0ad.gif", 10, 10)
    private val still = InlineImage("st1ll", "https://preview.redd.it/st1ll.png?width=640", "https://preview.redd.it/st1ll.png?s=f", 2000, 1000)

    private fun InlineImage.part() = BodyPart.Image(url, fullUrl, width, height)

    @Test
    fun gifReplacesItsLinkInPlace() {
        val html = """<div class="md"><p>before</p><p><a href="https://giphy.com/gifs/AbC123">https://giphy.com/gifs/AbC123</a></p><p>after</p></div>"""
        val parts = splitImages(html, listOf(giphy))
        assertEquals(3, parts.size)
        assertEquals(giphy.part(), parts[1])
        assert((parts[0] as BodyPart.Html).html.contains("before"))
        assert((parts[2] as BodyPart.Html).html.contains("after"))
    }

    @Test
    fun uploadMatchesPreviewLinkAndImageOnlyBodyHasNoText() {
        val html = """<div class="md"><p><a href="https://preview.redd.it/upl0ad.gif?width=10&amp;format=mp4&amp;s=x">gif</a></p></div>"""
        assertEquals(listOf(upload.part()), splitImages(html, listOf(upload)))
    }

    @Test
    fun staticUploadShowsInPlaceAndOpensFullSize() {
        val html = """<div class="md"><p>look</p><p><a href="https://preview.redd.it/st1ll.png?width=2000&amp;format=png&amp;auto=webp&amp;s=x">https://preview.redd.it/st1ll.png</a></p></div>"""
        val parts = splitImages(html, listOf(still))
        assertEquals(2, parts.size)
        assertEquals(BodyPart.Image(still.url, "https://preview.redd.it/st1ll.png?s=f", 2000, 1000), parts[1])
    }

    @Test
    fun directImageLinksInlineButOtherLinksStay() {
        val html = """<p>see <a href="https://i.imgur.com/cat.gif">this</a>, <a href="https://i.redd.it/dog.jpg">this</a> and <a href="https://example.com/a.png">that</a></p>"""
        val parts = splitImages(html, emptyList())
        assertEquals(BodyPart.Image("https://i.imgur.com/cat.gif", "https://i.imgur.com/cat.gif", 0, 0), parts[1])
        assertEquals(BodyPart.Image("https://i.redd.it/dog.jpg", "https://i.redd.it/dog.jpg", 0, 0), parts[3])
        assert((parts[4] as BodyPart.Html).html.contains("example.com"))
    }

    @Test
    fun unlinkedImageGoesAtTheEndAndPlainBodyIsUntouched() {
        assertEquals(listOf(BodyPart.Html("<p>hi</p>"), upload.part()), splitImages("<p>hi</p>", listOf(upload)))
        assertEquals(listOf(BodyPart.Html("<p>hi</p>")), splitImages("<p>hi</p>", emptyList()))
    }
}
