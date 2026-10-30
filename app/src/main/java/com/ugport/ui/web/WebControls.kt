package com.ugport.ui.web

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ugport.ui.components.GlassLevel
import com.ugport.ui.components.GlassSurface
import com.ugport.ui.components.glass

/** 端口页的悬浮控件与加载进度条。全部是无状态展示组件。 */

@Composable
internal fun CollapsedControlsBar(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
        val scrim = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f)
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(36.dp)
                .background(scrim, shape)
                .glass(shape, GlassLevel.Strong),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
internal fun WebFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scrim = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f)
    GlassSurface(
        modifier = Modifier
            .size(48.dp)
            .background(scrim, CircleShape),
        shape = CircleShape,
        level = GlassLevel.Strong,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}


@Composable
internal fun WebProgress(progress: Int, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = if (progress > 0) progress.coerceIn(0, 100) / 100f else 0f,
        animationSpec = tween(240, easing = LinearEasing),
        label = "webProgress",
    )
    val visible = progress in 1..99
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "webProgressAlpha",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        )
    }
}

