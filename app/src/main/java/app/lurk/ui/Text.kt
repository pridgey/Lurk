package app.lurk.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration

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
