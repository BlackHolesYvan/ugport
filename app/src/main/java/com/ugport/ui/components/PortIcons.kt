package com.ugport.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class IconRule(val keywords: List<String>, val icon: ImageVector)

private val RULES = listOf(
    IconRule(
        listOf(
        "影视", "视频", "电影", "影院", "播放",
        "音乐", "听书", "video", "movie", "jellyfin",
        "emby", "plex", "music", "audio", "ting",
    ),
        Icons.Filled.PlayArrow,
    ),
    IconRule(
        listOf("面板", "管理", "控制台", "设置", "portainer", "docker", "panel", "admin"),
        Icons.Filled.Settings,
    ),
    IconRule(
        listOf("密码", "密钥", "vault", "bitwarden", "keepass"),
        Icons.Filled.Lock,
    ),
    IconRule(
        listOf("邮件", "邮箱", "mail", "mailcow"),
        Icons.Filled.MailOutline,
    ),
    IconRule(
        listOf("日历", "日程", "calendar"),
        Icons.Filled.DateRange,
    ),
    IconRule(
        listOf("商城", "购物", "订单", "shop", "store"),
        Icons.Filled.ShoppingCart,
    ),
    IconRule(
        listOf("账号", "用户", "person", "account", "user"),
        Icons.Filled.Person,
    ),
    IconRule(
        listOf("首页", "主页", "导航", "home", "dashboard"),
        Icons.Filled.Home,
    ),
    IconRule(
        listOf("下载", "种子", "qbittorrent", "transmission", "aria2", "同步", "sync"),
        Icons.Filled.KeyboardArrowDown,
    ),
    IconRule(
        listOf("照片", "相册", "图库", "photo", "image"),
        Icons.Filled.Star,
    ),
    IconRule(
        listOf("文件", "网盘", "file", "cloud", "drive"),
        Icons.AutoMirrored.Filled.List,
    ),
)

private fun iconFor(name: String): ImageVector? {
    val lower = name.lowercase()
    return RULES.firstOrNull { rule -> rule.keywords.any { lower.contains(it) } }?.icon
}

private val AVATAR_GRADIENTS = listOf(
    Color(0xFF6C9BFF) to Color(0xFF2F6BE0),
    Color(0xFF5BCCBB) to Color(0xFF2E9E8F),
    Color(0xFFA091F7) to Color(0xFF6A55D8),
    Color(0xFFF59AB8) to Color(0xFFD9628A),
    Color(0xFFF7B877) to Color(0xFFDE7B33),
    Color(0xFF8FCF8F) to Color(0xFF469A4D),
)

private fun gradientFor(name: String): Pair<Color, Color> =
    AVATAR_GRADIENTS[(name.hashCode() and Int.MAX_VALUE) % AVATAR_GRADIENTS.size]

private fun initialOf(name: String): String {
    val ch = name.trim().firstOrNull() ?: return "·"
    return ch.uppercaseChar().toString()
}

@Composable
fun PortAvatar(
    name: String,
    size: Dp = 46.dp,
    modifier: Modifier = Modifier,
) {
    val icon = remember(name) { iconFor(name) }
    val colors = remember(name) { gradientFor(name) }
    val shape = RoundedCornerShape(30)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(colors.first, colors.second)))
            .border(0.5.dp, Color.White.copy(alpha = 0.28f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)
                    )
                ),
        )
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        } else {
            Text(
                text = initialOf(name),
                color = Color.White,
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
