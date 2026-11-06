package app.lurk.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Lurk's own palette: a coral accent on clean, neutral greys. Chrome stays monochrome;
// coral is kept for actions, votes and the things you've picked.
private val Coral = Color(0xFFFF5A36)
private val CoralLight = Color(0xFFFF8A6B)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE5401C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4DB),
    onPrimaryContainer = Color(0xFF3B0A00),
    secondary = Color(0xFF5F5F68),
    secondaryContainer = Color(0xFFECECEF),
    onSecondaryContainer = Color(0xFF1F1F23),
    tertiary = Color(0xFF1F8A70),
    background = Color(0xFFF6F6F7),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFF6F6F7),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFE9E9EC),
    onSurfaceVariant = Color(0xFF6B6B74),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF0F0F2),
    surfaceContainerHigh = Color(0xFFE9E9EC),
    surfaceContainerHighest = Color(0xFFE2E2E6),
    inverseSurface = Color(0xFF18181B),
    inverseOnSurface = Color(0xFFFAFAFA),
    outline = Color(0xFFA1A1AA),
    outlineVariant = Color(0xFFE4E4E7),
)

private val DarkColors = darkColorScheme(
    primary = CoralLight,
    onPrimary = Color(0xFF3B0A00),
    primaryContainer = Color(0xFF4E1A0D),
    onPrimaryContainer = Color(0xFFFFDDD3),
    secondary = Color(0xFFC4C4CC),
    secondaryContainer = Color(0xFF2A2A2E),
    onSecondaryContainer = Color(0xFFECECEE),
    tertiary = Color(0xFF6FD6BC),
    background = Color(0xFF0A0A0B),
    onBackground = Color(0xFFECECEE),
    surface = Color(0xFF0A0A0B),
    onSurface = Color(0xFFECECEE),
    surfaceVariant = Color(0xFF27272A),
    onSurfaceVariant = Color(0xFFA0A0A8),
    surfaceContainerLowest = Color(0xFF050506),
    surfaceContainerLow = Color(0xFF151517),
    surfaceContainer = Color(0xFF1B1B1E),
    surfaceContainerHigh = Color(0xFF242427),
    surfaceContainerHighest = Color(0xFF2E2E32),
    inverseSurface = Color(0xFFECECEE),
    inverseOnSurface = Color(0xFF18181B),
    outline = Color(0xFF5A5A62),
    outlineVariant = Color(0xFF26262A),
)

private val base = Typography()

private val LurkTypography = Typography(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = 0.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

private val LurkShapes = Shapes(
    // Text fields and menus.
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    // Dialogs and sheets.
    extraLarge = RoundedCornerShape(28.dp),
)

/** Upvote and downvote colors, shared by both themes. */
val UpvoteColor = Coral
val DownvoteColor = Color(0xFF6E8BFF)

@Composable
fun LurkTheme(darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val dark = darkOverride ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = LurkTypography,
        shapes = LurkShapes,
        content = content,
    )
}

/** A stable, pleasant color for a name (subreddit or user), for avatars. */
fun nameColor(name: String): Color {
    val palette = listOf(
        0xFFFF5A36, 0xFFFF9F1C, 0xFF2EC4B6, 0xFF3A86FF, 0xFF8338EC,
        0xFFE63977, 0xFF06A77D, 0xFF5E60CE, 0xFFF15BB5, 0xFF00A6FB,
    )
    return Color(palette[Math.floorMod(name.lowercase().hashCode(), palette.size)])
}
