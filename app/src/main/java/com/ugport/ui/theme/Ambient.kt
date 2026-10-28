package com.ugport.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class AmbientBlob(
    val color: Color,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
)

@Immutable
data class AmbientSpec(
    val gradient: List<Color>,
    val blobs: List<AmbientBlob>,
)

val LightAmbient = AmbientSpec(
    gradient = listOf(Color(0xFFEFF3FD), Color(0xFFF6F8FC), Color(0xFFFBF7F3)),
    blobs = listOf(
        AmbientBlob(Color(0xFF5B87FF).copy(alpha = 0.20f), -0.10f, -0.15f, 0.85f),
        AmbientBlob(Color(0xFF56C8B8).copy(alpha = 0.14f), 1.05f, 0.10f, 0.60f),
        AmbientBlob(Color(0xFF8E7BF0).copy(alpha = 0.12f), 0.15f, 0.60f, 0.70f),
    ),
)

val DarkAmbient = AmbientSpec(
    gradient = listOf(Color(0xFF0B0F16), Color(0xFF10151E), Color(0xFF0A0D13)),
    blobs = listOf(
        AmbientBlob(Color(0xFF2E63E8).copy(alpha = 0.30f), -0.10f, -0.15f, 0.85f),
        AmbientBlob(Color(0xFF1E8E82).copy(alpha = 0.18f), 1.05f, 0.10f, 0.60f),
        AmbientBlob(Color(0xFF6D4BC7).copy(alpha = 0.22f), 0.15f, 0.60f, 0.70f),
    ),
)
