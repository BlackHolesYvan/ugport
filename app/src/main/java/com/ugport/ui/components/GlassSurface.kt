package com.ugport.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.ugport.ui.theme.AppShape
import com.ugport.ui.theme.LocalGlass

enum class GlassLevel { Subtle, Card, Strong }

@Composable
fun Modifier.glass(
    shape: Shape = AppShape.card,
    level: GlassLevel = GlassLevel.Card,
): Modifier {
    val glass = LocalGlass.current
    val fillTop = when (level) {
        GlassLevel.Subtle -> glass.tileFill
        GlassLevel.Card -> glass.fillTop
        GlassLevel.Strong -> glass.fillTop.copy(alpha = (glass.fillTop.alpha + 0.12f).coerceAtMost(1f))
    }
    val fillBottom = when (level) {
        GlassLevel.Subtle -> glass.tileFill.copy(alpha = glass.tileFill.alpha * 0.75f)
        GlassLevel.Card -> glass.fillBottom
        GlassLevel.Strong -> glass.fillBottom.copy(alpha = (glass.fillBottom.alpha + 0.10f).coerceAtMost(1f))
    }
    val borderTop = when (level) {
        GlassLevel.Subtle -> glass.borderTop.copy(alpha = glass.borderTop.alpha * 0.75f)
        else -> glass.borderTop
    }
    // 磁贴数量多，只有卡片级才需要投影，避免每帧绘制大量阴影。
    val elevation = when {
        level == GlassLevel.Subtle -> 0.dp
        glass.isDark -> 6.dp
        else -> 10.dp
    }
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = glass.shadow,
            spotColor = glass.shadow,
        )
        .clip(shape)
        .background(Brush.verticalGradient(listOf(fillTop, fillBottom)))
        .background(Brush.linearGradient(listOf(glass.sheen, Color.Transparent, Color.Transparent)))
        .border(1.dp, Brush.verticalGradient(listOf(borderTop, glass.borderBottom)), shape)
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = AppShape.card,
    level: GlassLevel = GlassLevel.Card,
    padding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val base = modifier.glass(shape, level)
    Column(
        modifier = if (onClick != null) {
            base.clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
        } else {
            base
        }.padding(padding),
        content = content,
    )
}
