package app.lurk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons drawn for Lurk (24x24, 2px rounded strokes). The built-in Material set
 * has no comment, bookmark or share icons without pulling in the large extended set.
 */
object LurkIcons {
    val Upvote = icon("Upvote", "M9 18v-6H5l7-7 7 7h-4v6H9z")
    val UpvoteFilled = icon("UpvoteFilled", "M9 18v-6H5l7-7 7 7h-4v6H9z", filled = true)
    val Downvote = icon("Downvote", "M15 6v6h4l-7 7-7-7h4V6h6z")
    val DownvoteFilled = icon("DownvoteFilled", "M15 6v6h4l-7 7-7-7h4V6h6z", filled = true)
    val Comment = icon("Comment", "M7.9 20A9 9 0 1 0 4 16.1L2 22Z")
    val Bookmark = icon("Bookmark", "M19 21l-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z")
    val BookmarkFilled = icon("BookmarkFilled", "M19 21l-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z", filled = true)
    val Share = icon("Share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "M16 6l-4-4-4 4", "M12 2v13")
    val Sort = icon("Sort", "M3 16l4 4 4-4", "M7 20V4", "M21 8l-4-4-4 4", "M17 4v16")
    val External = icon("External", "M15 3h6v6", "M10 14L21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
    val Images = icon(
        "Images",
        "M18 22H4a2 2 0 0 1-2-2V6",
        "M22 13l-1.3-1.3a2.4 2.4 0 0 0-3.4 0L11 18",
        "M8 2h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z",
    )

    private const val SPEAKER =
        "M11 4.7a0.7 0.7 0 0 0-1.2-0.5L6.4 7.6A1.4 1.4 0 0 1 5.4 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.4a1.4 1.4 0 0 1 1 0.4l3.4 3.4A0.7 0.7 0 0 0 11 19.3z"
    val Volume = icon("Volume", SPEAKER, "M16 9a5 5 0 0 1 0 6", "M19.4 18.4a9 9 0 0 0 0-12.8")
    val Muted = icon("Muted", SPEAKER, "M22 9l-6 6", "M16 9l6 6")

    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { data ->
            builder.addPath(
                pathData = addPathNodes(data),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
