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
    val Fullscreen = icon("Fullscreen", "M15 3h6v6", "M9 21H3v-6", "M21 3l-7 7", "M3 21l7-7")
    val Copy = icon(
        "Copy",
        "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z",
        "M4 16a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2",
    )
    val Compass = icon(
        "Compass",
        "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z",
        "M16.24 7.76l-1.8 5.41a2 2 0 0 1-1.27 1.27L7.76 16.24l1.8-5.41a2 2 0 0 1 1.27-1.27z",
    )
    val Undo = icon("Undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val Eye = icon(
        "Eye",
        "M2.06 12.35a1 1 0 0 1 0-0.7 10.75 10.75 0 0 1 19.88 0 1 1 0 0 1 0 0.7 10.75 10.75 0 0 1-19.88 0",
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
    )
    val EyeOff = icon(
        "EyeOff",
        "M10.73 5.08a10.74 10.74 0 0 1 11.2 6.57 1 1 0 0 1 0 0.7 10.75 10.75 0 0 1-1.44 2.49",
        "M14.08 14.16a3 3 0 0 1-4.24-4.24",
        "M17.48 17.5a10.75 10.75 0 0 1-15.42-5.15 1 1 0 0 1 0-0.7 10.75 10.75 0 0 1 4.45-5.14",
        "M2 2l20 20",
    )
    /** A scoreboard: two panels, a score in each. */
    val Scores = icon(
        "Scores",
        "M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M12 5v14",
        "M7 9v6",
        "M17 9v6",
    )
    val Chevron = icon("Chevron", "M9 6l6 6-6 6")
    val Pause = icon("Pause", "M7 5h3v14H7z", "M14 5h3v14h-3z", filled = true)

    val ChevronDown = icon("ChevronDown", "M6 9l6 6 6-6")
    val Home = icon(
        "Home",
        "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8",
        "M3 10a2 2 0 0 1 0.71-1.53l7-6a2 2 0 0 1 2.58 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
    )
    val Search = icon("Search", "M19 11a8 8 0 1 1-16 0a8 8 0 1 1 16 0z", "M21 21l-4.3-4.3")
    val Plus = icon("Plus", "M12 5v14", "M5 12h14")
    val Check = icon("Check", "M20 6L9 17l-5-5")
    val Refresh = icon(
        "Refresh",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5",
    )
    val List = icon("List", "M8 6h13", "M8 12h13", "M8 18h13", "M3 6h0.01", "M3 12h0.01", "M3 18h0.01")
    val Inbox = icon(
        "Inbox",
        "M22 12h-6l-2 3h-4l-2-3H2",
        "M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
    )
    val Globe = icon(
        "Globe",
        "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z",
        "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
        "M2 12h20",
    )
    val Download = icon("Download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3")
    val Terminal = icon("Terminal", "M4 17l6-6-6-6", "M12 19h8")
    val Rss = icon("Rss", "M4 11a9 9 0 0 1 9 9", "M4 4a16 16 0 0 1 16 16", "M6 19a1 1 0 1 1-2 0a1 1 0 1 1 2 0z")
    val Lock = icon(
        "Lock",
        "M7 11V7a5 5 0 0 1 10 0v4",
        "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z",
    )
    val LogOut = icon("LogOut", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "M16 17l5-5-5-5", "M21 12H9")
    val Info = icon("Info", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z", "M12 16v-4", "M12 8h0.01")
    val Alert = icon(
        "Alert",
        "M21.73 18l-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
        "M12 9v4",
        "M12 17h0.01",
    )
    val WifiOff = icon(
        "WifiOff",
        "M12 20h0.01", "M8.5 16.43a5 5 0 0 1 7 0", "M2 8.82a15 15 0 0 1 4.18-2.65",
        "M19 12.86a10 10 0 0 0-2.18-1.42", "M22 8.82a15 15 0 0 0-11.29-3.76", "M5 12.86a10 10 0 0 1 5.17-2.69", "M2 2l20 20",
    )
    val Empty = icon(
        "Empty",
        "M22 12h-6l-2 3h-4l-2-3H2",
        "M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
    )
    val Chrome = icon(
        "Chrome",
        "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z",
        "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z",
        "M21.17 8H12", "M3.95 6.06L8.54 14", "M10.88 21.94L15.46 14",
    )

    val Trending = icon("Trending", "M22 7l-8.5 8.5-5-5L2 17", "M16 7h6v6")
    val MapPin = icon(
        "MapPin",
        "M20 10c0 4.99-5.54 10.19-7.4 11.8a1 1 0 0 1-1.2 0C9.54 20.19 4 14.99 4 10a8 8 0 0 1 16 0",
        "M15 10a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
    )

    /** The icon for a fixed feed (Popular, All, Local), or null to use its letter avatar. */
    fun feed(community: String): ImageVector? = when (community.lowercase().removePrefix(":")) {
        "popular" -> Trending
        "all" -> Globe
        "local" -> MapPin
        else -> null
    }

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
