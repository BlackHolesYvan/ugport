package com.ugport.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 端口页顶部的 WebGPU 能力提示条。
 *
 * 只作为 overlay 覆盖在 WebView 上方，不插入 Column，避免改变 WebView 高度
 * 触发页面 resize（qBittorrent 等依赖 vh 的页面会重新塌陷）。
 */
@Composable
fun WebGpuHint(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier,
        level = GlassLevel.Strong,
        padding = PaddingValues(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("知道了", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
