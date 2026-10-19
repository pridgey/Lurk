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

// Lurk's own palette: a warm coral accent on soft, slightly warm neutrals.
private val Coral = Color(0xFFFF5A36)
private val CoralLight = Color(0xFFFF8A6B)

private val LightColors = lightColorScheme(
    primary = Color(0xFFE5401C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDD3),
    onPrimaryContainer = Color(0xFF3B0A00),
    secondary = Color(0xFF6B5B57),
    secondaryContainer = Color(0xFFF3E4DF),
    onSecondaryContainer = Color(0xFF261915),
    tertiary = Color(0xFF2F7D6B),
    background = Color(0xFFF6F3F1),
    onBackground = Color(0xFF1C1A19),
    surface = Color(0xFFF6F3F1),
    onSurface = Color(0xFF1C1A19),
    surfaceVariant = Color(0xFFECE6E3),
    onSurfaceVariant = Color(0xFF6F6763),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0ECEA),
    surfaceContainerHigh = Color(0xFFEAE5E2),
    surfaceContainerHighest = Color(0xFFE3DDDA),
    outline = Color(0xFFB9B0AC),
    outlineVariant = Color(0xFFE2DBD8),
)

private val DarkColors = darkColorScheme(
    primary = CoralLight,
    onPrimary = Color(0xFF3B0A00),
    primaryContainer = Color(0xFF5A1B0B),
    onPrimaryContainer = Color(0xFFFFDDD3),
    secondary = Color(0xFFD8C2BC),
    secondaryContainer = Color(0xFF3A302D),
    onSecondaryContainer = Color(0xFFF3E4DF),
    tertiary = Color(0xFF7DD3BE),
    background = Color(0xFF0F0E0E),
    onBackground = Color(0xFFECE6E3),
    surface = Color(0xFF0F0E0E),
    onSurface = Color(0xFFECE6E3),
    surfaceVariant = Color(0xFF2A2726),
    onSurfaceVariant = Color(0xFFA59D99),
    surfaceContainerLowest = Color(0xFF0A0909),
    surfaceContainerLow = Color(0xFF191716),
    surfaceContainer = Color(0xFF1E1C1B),
    surfaceContainerHigh = Color(0xFF282524),
    surfaceContainerHighest = Color(0xFF33302E),
    outline = Color(0xFF6A6360),
    outlineVariant = Color(0xFF2E2B2A),
)

private val base = Typography()

private val LurkTypography = Typography(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

private val LurkShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
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
