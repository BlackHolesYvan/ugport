package com.ugport.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FE0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0A2A66),
    secondary = Color(0xFF4A6285),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3EAF6),
    onSecondaryContainer = Color(0xFF1B2739),
    tertiary = Color(0xFF2F7F74),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD6EFEA),
    onTertiaryContainer = Color(0xFF0C3A34),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF3F6FC),
    onBackground = Color(0xFF11151B),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF11151B),
    surfaceVariant = Color(0xFFE6EBF3),
    onSurfaceVariant = Color(0xFF5A6472),
    surfaceTint = Color(0xFF1F5FE0),
    outline = Color(0xFFC3CBD9),
    outlineVariant = Color(0xFFDCE2EC),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2C3138),
    inverseOnSurface = Color(0xFFF1F3F7),
    inversePrimary = Color(0xFFAEC6FF),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDE3ED),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FD),
    surfaceContainer = Color(0xFFF1F4FA),
    surfaceContainerHigh = Color(0xFFEAEFF7),
    surfaceContainerHighest = Color(0xFFE3E9F3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC0FF),
    onPrimary = Color(0xFF0A2F63),
    primaryContainer = Color(0xFF16386E),
    onPrimaryContainer = Color(0xFFD7E6FF),
    secondary = Color(0xFFA7BCD9),
    onSecondary = Color(0xFF12233A),
    secondaryContainer = Color(0xFF22344C),
    onSecondaryContainer = Color(0xFFD3E1F7),
    tertiary = Color(0xFF8FD3C8),
    onTertiary = Color(0xFF06332E),
    tertiaryContainer = Color(0xFF16453F),
    onTertiaryContainer = Color(0xFFC9EFE8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0E14),
    onBackground = Color(0xFFE6EAF1),
    surface = Color(0xFF10151C),
    onSurface = Color(0xFFE6EAF1),
    surfaceVariant = Color(0xFF2A3140),
    onSurfaceVariant = Color(0xFFA9B4C4),
    surfaceTint = Color(0xFF9EC0FF),
    outline = Color(0xFF6B7688),
    outlineVariant = Color(0xFF333C4A),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE6EAF1),
    inverseOnSurface = Color(0xFF2A3140),
    inversePrimary = Color(0xFF1F5FE0),
    surfaceBright = Color(0xFF2C333D),
    surfaceDim = Color(0xFF0A0E14),
    surfaceContainerLowest = Color(0xFF05080C),
    surfaceContainerLow = Color(0xFF0D1218),
    surfaceContainer = Color(0xFF131922),
    surfaceContainerHigh = Color(0xFF1A2129),
    surfaceContainerHighest = Color(0xFF222A35),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object AppShape {
    val card = RoundedCornerShape(22.dp)
    val tile = RoundedCornerShape(20.dp)
    val field = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(50)
}

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun UgPortTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGlass provides if (darkTheme) DarkGlassTokens else LightGlassTokens) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
