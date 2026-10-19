package app.lurk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.lurk.data.InlineGif
import coil.compose.AsyncImage

/** "3h", "2d", "5mo" since a Unix time in seconds. */
fun ago(createdUtc: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val s = (now - createdUtc).coerceAtLeast(0)
    return when {
        s < 60 -> "now"
        s < 3_600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3_600}h"
        s < 2_592_000 -> "${s / 86_400}d"
        s < 31_536_000 -> "${s / 2_592_000}mo"
        else -> "${s / 31_536_000}y"
    }
}

/** 950, 1.2k, 34k, 1.5m */
fun compact(n: Int): String = when {
    n < 1_000 && n > -1_000 -> n.toString()
    n < 10_000 && n > -10_000 -> "%.1fk".format(n / 1_000f).replace(".0k", "k")
    n < 1_000_000 && n > -1_000_000 -> "${n / 1_000}k"
    else -> "%.1fm".format(n / 1_000_000f).replace(".0m", "m")
}

private val preBlock = Regex("<pre>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)

/**
 * Reddit's rendered markdown (body_html / selftext_html) as text with tappable links.
 * Code blocks keep their line breaks; tables and the like come out as plain text.
 */
@Composable
fun HtmlText(
    html: String,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val latestOnLink by rememberUpdatedState(onLink)
    val text = remember(html, linkColor) {
        val prepared = preBlock.replace(html) { m ->
            "<pre>" + m.groupValues[1].trimEnd('\n').replace("\n", "<br>").replace(" ", "&nbsp;") + "</pre>"
        }
        val parsed = AnnotatedString.fromHtml(
            prepared,
            linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            linkInteractionListener = { link -> (link as? LinkAnnotation.Url)?.let { latestOnLink(it.url) } },
        )
        val end = parsed.text.indexOfLast { !it.isWhitespace() } + 1
        val start = parsed.text.indexOfFirst { !it.isWhitespace() }.coerceIn(0, end)
        parsed.subSequence(start, end)
    }
    Text(text, modifier = modifier, style = style)
}

/** A piece of a comment body: rendered markdown, or a GIF shown in its place. */
sealed interface BodyPart {
    data class Html(val html: String) : BodyPart
    data class Gif(val url: String, val width: Int, val height: Int) : BodyPart
}

private val anchor = Regex("""<a\s[^>]*?href="([^"]*)"[^>]*>.*?</a>""", RegexOption.DOT_MATCHES_ALL)
private val gifLinkHosts = setOf("i.redd.it", "i.imgur.com", "media.giphy.com")
private val tag = Regex("<[^>]*>")

/**
 * Splits [html] at links to its embedded [gifs] (and direct .gif links on image hosts),
 * so each GIF shows where it was linked. Embedded GIFs the body never links to go at the end.
 */
fun splitGifs(html: String, gifs: List<InlineGif>): List<BodyPart> {
    val parts = mutableListOf<BodyPart>()
    val shown = mutableSetOf<InlineGif>()
    var last = 0
    for (m in anchor.findAll(html)) {
        val href = m.groupValues[1].replace("&amp;", "&")
        val embedded = gifs.firstOrNull { it.token.isNotEmpty() && href.contains(it.token) }
        val part = when {
            embedded != null -> BodyPart.Gif(embedded.url, embedded.width, embedded.height).also { shown += embedded }
            isDirectGif(href) -> BodyPart.Gif(href, 0, 0)
            else -> continue
        }
        parts += BodyPart.Html(html.substring(last, m.range.first))
        parts += part
        last = m.range.last + 1
    }
    parts += BodyPart.Html(html.substring(last))
    gifs.filter { it !in shown }.forEach { parts += BodyPart.Gif(it.url, it.width, it.height) }
    return parts.filter { it !is BodyPart.Html || tag.replace(it.html, "").replace("&nbsp;", "").isNotBlank() }
}

private fun isDirectGif(href: String): Boolean {
    val m = Regex("""^https?://([^/?#]+)(/[^?#]*)""").find(href) ?: return false
    return m.groupValues[1].lowercase() in gifLinkHosts && m.groupValues[2].lowercase().endsWith(".gif")
}

/** A comment body with its GIFs playing inline. Tapping a GIF opens it full screen. */
@Composable
fun CommentBody(
    html: String,
    gifs: List<InlineGif>,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val parts = remember(html, gifs) { splitGifs(html, gifs) }
    if (parts.size == 1 && parts[0] is BodyPart.Html) {
        HtmlText(html, onLink, modifier, style)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (part in parts) {
            when (part) {
                is BodyPart.Html -> HtmlText(part.html, onLink, style = style)
                is BodyPart.Gif -> AsyncImage(
                    model = part.url,
                    contentDescription = "GIF",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .widthIn(max = 280.dp)
                        .then(
                            if (part.width > 0 && part.height > 0) Modifier.aspectRatio(part.width.toFloat() / part.height)
                            else Modifier.heightIn(min = 48.dp),
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onLink(part.url) },
                )
            }
        }
    }
}
