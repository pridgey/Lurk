package app.lurk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lurk.data.Article
import app.lurk.data.ArticleBlock
import app.lurk.data.ReaderScreen
import coil.compose.AsyncImage
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Reader text sizes, as steps of the body size. */
private val TextSteps = listOf(0.88f, 1f, 1.12f, 1.26f)

/**
 * Reader view: the article alone, in Lurk's typography, with a size control and a bar that
 * shows how far through it you are. Links open as any other link.
 */
@Composable
fun ReaderView(screen: ReaderScreen, onLink: (String) -> Unit, onImage: (String) -> Unit, onOriginal: () -> Unit) {
    val article = screen.article
    Box(Modifier.fillMaxSize()) {
        when {
            article != null -> ArticleBody(screen, article, onLink, onImage, onOriginal)
            screen.error != null -> EmptyState(
                icon = LurkIcons.Book,
                title = "Reader view isn't available",
                body = screen.error,
                action = "Open the page",
                onAction = onOriginal,
                modifier = Modifier.padding(top = 48.dp),
                secondary = { TextButton(onClick = { screen.load() }, modifier = Modifier.padding(top = 4.dp)) { Text("Try again") } },
            )
            else -> ReaderSkeleton(screen.postTitle)
        }
    }
}

@Composable
private fun ArticleBody(screen: ReaderScreen, article: Article, onLink: (String) -> Unit, onImage: (String) -> Unit, onOriginal: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(1) }
    val scale = TextSteps[step]
    val body = MaterialTheme.typography.bodyLarge.let { it.copy(fontSize = (17 * scale).sp, lineHeight = (28 * scale).sp) }
    val progress by remember(screen) {
        derivedStateOf {
            val info = screen.listState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 1) 0f else {
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (last >= total - 1) 1f else (screen.listState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = screen.listState,
            contentPadding = PaddingValues(bottom = 48.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "head") { ArticleHead(article, step, onStep = { step = it }) }
            items(article.blocks.withIndex().toList(), key = { it.index }) { (_, block) ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 22.dp)) {
                        Block(block, body, scale, onLink, onImage)
                    }
                }
            }
            item(key = "end") { ArticleEnd(article, onOriginal) }
        }
        LinearProgressIndicator(
            progress = { progress },
            trackColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun ArticleHead(article: Article, step: Int, onStep: (Int) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(article.site ?: article.host, 22.dp)
                Text(
                    article.site ?: article.host,
                    style = MaterialTheme.typography.labelLarge,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                TextSizeControl(step, onStep)
            }
            article.title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 27.sp, lineHeight = 34.sp),
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            val meta = listOfNotNull(article.byline, published(article.published), "${article.minutes} min read")
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                modifier = Modifier.padding(top = 10.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))
        }
    }
}

/** Smaller and larger text, as two halves of one pill. */
@Composable
private fun TextSizeControl(step: Int, onStep: (Int) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.height(34.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 42.dp, height = 34.dp)
                    .clickable(enabled = step > 0, onClickLabel = "Smaller text") { onStep(step - 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text("A", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (step > 0) 1f else 0.35f))
            }
            Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Box(
                Modifier
                    .size(width = 42.dp, height = 34.dp)
                    .clickable(enabled = step < TextSteps.lastIndex, onClickLabel = "Larger text") { onStep(step + 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text("A", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (step < TextSteps.lastIndex) 1f else 0.35f))
            }
        }
    }
}

@Composable
private fun Block(block: ArticleBlock, body: TextStyle, scale: Float, onLink: (String) -> Unit, onImage: (String) -> Unit) {
    when (block) {
        is ArticleBlock.Heading -> HtmlText(
            block.html,
            onLink,
            style = (if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                .let { it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) },
            modifier = Modifier.padding(top = 26.dp, bottom = 4.dp),
        )
        is ArticleBlock.Paragraph -> HtmlText(block.html, onLink, style = body, modifier = Modifier.padding(vertical = 9.dp))
        is ArticleBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 12.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)))
            HtmlText(
                block.html,
                onLink,
                style = body.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        is ArticleBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 18).dp, top = 4.dp, bottom = 4.dp)) {
            Text(
                if (block.ordered) "${block.number}." else "•",
                style = body.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.width(26.dp),
            )
            HtmlText(block.html, onLink, style = body)
        }
        is ArticleBlock.Code -> Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) {
            Text(
                block.text,
                fontFamily = FontFamily.Monospace,
                fontSize = (13.5f * scale).sp,
                lineHeight = (20 * scale).sp,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
            )
        }
        is ArticleBlock.Image -> Column(Modifier.padding(vertical = 14.dp)) {
            var aspect by remember(block.url) {
                mutableStateOf(if (block.width > 0 && block.height > 0) block.width.toFloat() / block.height else null)
            }
            AsyncImage(
                model = block.url,
                contentDescription = block.caption,
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (size.width > 0f && size.height > 0f) aspect = size.width / size.height
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio((aspect ?: 1.6f).coerceIn(0.5f, 3f))
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { onImage(block.url) },
            )
            block.caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
                )
            }
        }
        ArticleBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline)) }
            }
        }
    }
}

@Composable
private fun ArticleEnd(article: Article, onOriginal: () -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Simplified from ${article.host} for reading. Something missing?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp),
            )
            TextButton(onClick = onOriginal) {
                Icon(LurkIcons.External, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open the original")
            }
        }
    }
}

/** Placeholder lines while the article loads, under the post's title if it's known. */
@Composable
private fun ReaderSkeleton(title: String?) {
    val block = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp)) {
        Box(Modifier.width(120.dp).height(12.dp).clip(CircleShape).background(block))
        if (title != null) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 18.dp))
        } else {
            Box(Modifier.padding(top = 18.dp).fillMaxWidth(0.9f).height(24.dp).clip(CircleShape).background(block))
            Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.6f).height(24.dp).clip(CircleShape).background(block))
        }
        Box(Modifier.padding(top = 14.dp).width(180.dp).height(12.dp).clip(CircleShape).background(block))
        Spacer(Modifier.height(28.dp))
        repeat(3) {
            listOf(1f, 0.96f, 0.98f, 0.7f).forEach { w ->
                Box(Modifier.padding(top = 10.dp).fillMaxWidth(w).height(14.dp).clip(CircleShape).background(block))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun published(raw: String?): String? {
    raw ?: return null
    return runCatching {
        OffsetDateTime.parse(raw).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }.getOrNull()
}
