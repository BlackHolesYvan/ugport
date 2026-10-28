package com.ugport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ugport.data.model.AppData
import com.ugport.network.Diagnostics
import com.ugport.util.BatteryOptimization

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    data: AppData,
    diagnostics: Diagnostics,
    onBack: () -> Unit,
    onExternalLinkModeChange: (String) -> Unit,
    onClearCookies: () -> Unit,
    onClearSession: (String) -> Unit,
) {
    val lines by diagnostics.lines.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showLinkModeDialog by remember { mutableStateOf(false) }
    var ignoringBattery by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBattery = BatteryOptimization.isIgnoring(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun copyLogs() {
        val header = "绿联快连 ${com.ugport.BuildConfig.VERSION_NAME}\n" +
            "Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MODEL}\n"
        val text = header + lines.joinToString("\n")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("UgPort 诊断日志", text))
        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置与诊断") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("登录状态", style = MaterialTheme.typography.titleMedium)
                        data.nasList.forEach { nas ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(nas.name, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { onClearSession(nas.id) }) {
                                    Text("清除")
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        TextButton(onClick = onClearCookies) { Text("清除所有 WebView Cookie") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("网页链接", style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("外部链接打开方式", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { showLinkModeDialog = true }) {
                                Text(linkModeLabel(data.externalLinkMode))
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("后台播放", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (ignoringBattery) {
                                "已允许后台运行，锁屏后不易被系统中断"
                            } else {
                                "未允许后台运行，锁屏后可能被系统中断"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ignoringBattery) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { BatteryOptimization.openRequest(context) }) {
                                Text("去设置")
                            }
                            TextButton(onClick = { BatteryOptimization.openAppDetails(context) }) {
                                Text("应用详情")
                            }
                        }
                        Text(
                            "小米/红米还需要：设置 → 应用设置 → 绿联快连 → 省电策略 → 无限制，并允许自启动；最近任务可下拉锁定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("关于", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "绿联快连 ${com.ugport.BuildConfig.VERSION_NAME}\n仅在本机加密保存账号密码，用于自动登录你的 NAS。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "诊断日志（${lines.size} 条）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Row {
                        TextButton(onClick = { copyLogs() }, enabled = lines.isNotEmpty()) {
                            Text("复制日志")
                        }
                        TextButton(onClick = diagnostics::clear) { Text("清空") }
                    }
                }
            }
            if (lines.isEmpty()) {
                item {
                    Text(
                        "暂无日志。打开一次端口后这里会显示登录与 GetUid 的结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(lines.asReversed()) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    if (showLinkModeDialog) {
        AlertDialog(
            onDismissRequest = { showLinkModeDialog = false },
            title = { Text("外部链接打开方式") },
            text = {
                Column {
                    LINK_MODES.forEach { (value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = data.externalLinkMode == value,
                                onClick = {
                                    onExternalLinkModeChange(value)
                                    showLinkModeDialog = false
                                },
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLinkModeDialog = false }) { Text("关闭") }
            },
        )
    }
}

private val LINK_MODES = listOf(
    "ask" to "每次询问",
    "internal" to "应用内打开",
    "external" to "使用浏览器打开",
)

private fun linkModeLabel(mode: String): String =
    LINK_MODES.firstOrNull { it.first == mode }?.second ?: "每次询问"
