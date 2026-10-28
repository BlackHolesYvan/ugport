package com.ugport.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class PillTone { Neutral, Success, Accent }

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Neutral,
) {
    val scheme = MaterialTheme.colorScheme
    val background: Color
    val content: Color
    when (tone) {
        PillTone.Neutral -> {
            background = scheme.surfaceContainerHigh
            content = scheme.onSurfaceVariant
        }
        PillTone.Success -> {
            background = scheme.tertiaryContainer
            content = scheme.onTertiaryContainer
        }
        PillTone.Accent -> {
            background = scheme.primaryContainer
            content = scheme.onPrimaryContainer
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        modifier = modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
