package com.ugport.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ugport.ui.theme.DarkAmbient
import com.ugport.ui.theme.LightAmbient
import com.ugport.ui.theme.LocalGlass
import kotlin.math.max

/**
 * 静态渐变氛围底：一层纵向渐变 + 三个柔光色斑。
 * 不做动画，避免低端机持续掉帧。
 */
@Composable
fun AmbientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val spec = if (LocalGlass.current.isDark) DarkAmbient else LightAmbient
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(brush = Brush.verticalGradient(spec.gradient))
                spec.blobs.forEach { blob ->
                    val center = Offset(size.width * blob.centerX, size.height * blob.centerY)
                    val radius = max(size.width, size.height) * blob.radius
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(blob.color, Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                        center = center,
                    )
                }
            },
        content = content,
    )
}
