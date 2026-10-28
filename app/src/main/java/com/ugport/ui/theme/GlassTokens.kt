package com.ugport.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class GlassTokens(
    val fillTop: Color,
    val fillBottom: Color,
    val sheen: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val shadow: Color,
    val hairline: Color,
    val tileFill: Color,
    val scrim: Color,
    val isDark: Boolean,
)

val LightGlassTokens = GlassTokens(
    fillTop = Color.White.copy(alpha = 0.62f),
    fillBottom = Color.White.copy(alpha = 0.40f),
    sheen = Color.White.copy(alpha = 0.10f),
    borderTop = Color.White.copy(alpha = 0.90f),
    borderBottom = Color.White.copy(alpha = 0.35f),
    shadow = Color(0xFF1B2A4A).copy(alpha = 0.10f),
    hairline = Color(0xFF11151B).copy(alpha = 0.06f),
    tileFill = Color.White.copy(alpha = 0.55f),
    scrim = Color(0xFF0A0E14).copy(alpha = 0.30f),
    isDark = false,
)

val DarkGlassTokens = GlassTokens(
    fillTop = Color.White.copy(alpha = 0.10f),
    fillBottom = Color.White.copy(alpha = 0.04f),
    sheen = Color.White.copy(alpha = 0.06f),
    borderTop = Color.White.copy(alpha = 0.18f),
    borderBottom = Color.White.copy(alpha = 0.05f),
    shadow = Color.Black.copy(alpha = 0.50f),
    hairline = Color.White.copy(alpha = 0.08f),
    tileFill = Color.White.copy(alpha = 0.06f),
    scrim = Color.Black.copy(alpha = 0.45f),
    isDark = true,
)

val LocalGlass = staticCompositionLocalOf { LightGlassTokens }
