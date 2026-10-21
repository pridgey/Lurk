package app.lurk.data

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Markdown (as Lemmy sends it) to the HTML that [app.lurk.ui.HtmlText] shows, with
 * Lemmy's extras turned into plain Markdown first: spoiler blocks and
 * !community@instance links.
 */
object Markdown {
    private val extensions = listOf(
        StrikethroughExtension.create(),
        TablesExtension.create(),
        AutolinkExtension.create(),
    )
    private val parser = Parser.builder().extensions(extensions).build()
    // Raw HTML in posts is shown as text, never run.
    private val renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).build()

    fun toHtml(markdown: String): String = renderer.render(parser.parse(prepare(markdown)))

    /** "::: spoiler Title" ... ":::" */
    private val spoiler = Regex("""(?m)^:::\s*spoiler\s*(.*)\n([\s\S]*?)\n:::\s*$""")

    /** !community@instance, not inside a word, link or image. */
    private val communityMention = Regex("""(?<![\w/\[!(])!([A-Za-z0-9_]+)@([A-Za-z0-9.-]+\.[A-Za-z]{2,})\b""")

    private fun prepare(markdown: String): String {
        // No collapsing in the native text yet, so a spoiler shows as a labeled quote.
        var md = spoiler.replace(markdown) { m ->
            val title = m.groupValues[1].trim().ifEmpty { "Spoiler" }
            val body = m.groupValues[2].lines().joinToString("\n") { "> $it" }
            "> **Spoiler: $title**\n>\n$body"
        }
        md = communityMention.replace(md) { m ->
            val (name, host) = m.destructured
            "[!$name@$host](https://$host/c/$name)"
        }
        return md
    }

    private val imgTag = Regex("""<img\s[^>]*?src="([^"]+)"[^>]*>""")

    /** Every image in rendered [html], so the body can show them in place. */
    fun images(html: String): List<InlineImage> =
        imgTag.findAll(html).map { it.groupValues[1].replace("&amp;", "&") }.distinct()
            .map { InlineImage(id = it, url = it, fullUrl = it, width = 0, height = 0) }
            .toList()
}
