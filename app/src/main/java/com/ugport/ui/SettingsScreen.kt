package com.ugport.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ugport.BuildConfig
import com.ugport.data.model.AppData
import com.ugport.data.model.NasProfile
import com.ugport.data.model.ThemeMode
import com.ugport.network.Diagnostics
import com.ugport.ui.components.ConfirmDialog
import com.ugport.ui.components.GlassActionSheet
import com.ugport.ui.components.PillOption
import com.ugport.ui.components.SectionCard
import com.ugport.ui.components.SegmentedPills
import com.ugport.ui.components.SectionDivider
import com.ugport.ui.components.SettingRow
import com.ugport.ui.components.SheetAction
import com.ugport.ui.components.UgScaffold
import com.ugport.ui.components.UgTopBar
import com.ugport.ui.theme.AppShape
import com.ugport.util.BatteryOptimization

@Composable
fun SettingsScreen(
    data: AppData,
    diagnostics: Diagnostics,
    onBack: () -> Unit,
    onExternalLinkModeChange: (String) -> Unit,
    onThemeModeChange: (String) -> Unit,
    onClearCookies: () -> Unit,
    onClearSession: (String) -> Unit,
) {
    val lines by diagnostics.lines.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showLinkSheet by remember { mutableStateOf(false) }
    var pendingClear by remember { mutableStateOf<NasProfile?>(null) }
    var confirmClearCookies by remember { mutableStateOf(false) }
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
        // 头信息只放版本与系统版本；设备型号、账号等都不进剪贴板。
        val header = "UgPort " + BuildConfig.VERSION_NAME + "\n" +
            "Android " + android.os.Build.VERSION.RELEASE +
            " (SDK " + android.os.Build.VERSION.SDK_INT + ")\n"
        val text = header + lines.joinToString("\n")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("UgPort 诊断日志", text))
        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    UgScaffold(
        topBar = {
            UgTopBar(title = "设置与诊断", onBack = onBack)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                SectionCard(title = "登录状态") {
                    if (data.nasList.isEmpty()) {
                        SettingRow(title = "还没有 NAS", subtitle = "先在首页添加一台 NAS")
                    }
                    data.nasList.forEach { nas ->
                        SettingRow(
                            title = nas.name,
                            subtitle = if (nas.tokenEnc.isNotBlank()) "凭证已保存" else "未登录",
                            trailing = {
                                TextButton(
                                    onClick = { pendingClear = nas },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Text(
                                        text = "清除",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                        )
                        SectionDivider()
                    }
                    SettingRow(
                        title = "清除所有 WebView Cookie",
                        subtitle = "端口页面登录态异常时使用",
                        showChevron = true,
                        onClick = { confirmClearCookies = true },
                    )
                }
            }

            item {
                SectionCard(title = "网页链接") {
                    SettingRow(
                        title = "外部链接打开方式",
                        value = linkModeLabel(data.externalLinkMode),
                        showChevron = true,
                        onClick = { showLinkSheet = true },
                    )
                }
            }

            item {
                SectionCard(
                    title = "外观",
                    footer = "「自适应」跟随系统的深色模式自动切换。",
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        SegmentedPills(
                            options = THEME_MODES,
                            selected = ThemeMode.sanitize(data.themeMode),
                            onSelect = onThemeModeChange,
                        )
                    }
                }
            }

            item {
                SectionCard(
                    title = "后台播放",
                    footer = "部分国产系统还需要：设置 → 应用设置 → 绿联快连 → 省电策略 → 无限制，并允许自启动；最近任务可下拉锁定。",
                ) {
                    SettingRow(
                        title = if (ignoringBattery) "已允许后台运行" else "未允许后台运行",
                        subtitle = if (ignoringBattery) {
                            "锁屏后不易被系统中断"
                        } else {
                            "锁屏后可能被系统中断播放"
                        },
                        leading = { StatusDot(ok = ignoringBattery) },
                    )
                    SectionDivider()
                    SettingRow(
                        title = "去设置忽略电池优化",
                        showChevron = true,
                        onClick = { BatteryOptimization.openRequest(context) },
                    )
                    SectionDivider()
                    SettingRow(
                        title = "打开应用详情",
                        showChevron = true,
                        onClick = { BatteryOptimization.openAppDetails(context) },
                    )
                }
            }

            item {
                SectionCard(title = "关于") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "绿联快连 " + BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "仅在本机加密保存账号密码，用于自动登录你的 NAS。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item {
                DiagnosticsLogCard(
                    count = lines.size,
                    lines = lines,
                    onCopy = { copyLogs() },
                    onClear = diagnostics::clear,
                )
            }
        }
    }

    if (showLinkSheet) {
        GlassActionSheet(
            onDismissRequest = { showLinkSheet = false },
            title = "外部链接打开方式",
            actions = LINK_MODES.map { (value, label) ->
                SheetAction(
                    label = label,
                    selected = data.externalLinkMode == value,
                    onClick = {
                        onExternalLinkModeChange(value)
                        showLinkSheet = false
                    },
                )
            },
        )
    }

    val clearTarget = pendingClear
    if (clearTarget != null) {
        ConfirmDialog(
            title = "清除登录状态",
            message = "将清除「" + clearTarget.name + "」在本机保存的凭证，下次打开端口会重新登录。",
            confirmText = "清除",
            destructive = true,
            onConfirm = {
                onClearSession(clearTarget.id)
                pendingClear = null
            },
            onDismiss = { pendingClear = null },
        )
    }

    if (confirmClearCookies) {
        ConfirmDialog(
            title = "清除所有 Cookie",
            message = "所有端口的网页登录态都会被重置，确定继续？",
            confirmText = "清除",
            destructive = true,
            onConfirm = {
                confirmClearCookies = false
                onClearCookies()
            },
            onDismiss = { confirmClearCookies = false },
        )
    }
}

@Composable
private fun DiagnosticsLogCard(
    count: Int,
    lines: List<String>,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    SectionCard(
        title = "诊断日志 · " + count + " 条",
        footer = "日志可能包含 NAS 域名与端口，分享到 issue 前请先检查。",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "登录、鉴权与页面加载的关键结果",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onCopy,
                enabled = count > 0,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("复制", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = onClear,
                enabled = count > 0,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("清空", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (count == 0) {
            Text(
                text = "暂无日志。打开一次端口后，这里会显示登录与 GetUid 的结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .heightIn(min = 120.dp, max = 240.dp)
                    .clip(AppShape.field)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(lines.asReversed()) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    Box(
        Modifier
            .size(9.dp)
            .background(
                if (ok) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
                CircleShape,
            ),
    )
    Spacer(Modifier.width(2.dp))
}

private val THEME_MODES = listOf(
    PillOption(ThemeMode.LIGHT, "白天"),
    PillOption(ThemeMode.DARK, "黑夜"),
    PillOption(ThemeMode.SYSTEM, "自适应"),
)

private val LINK_MODES = listOf(
    "ask" to "每次询问",
    "internal" to "应用内打开",
    "external" to "使用浏览器打开",
)

private fun linkModeLabel(mode: String): String =
    LINK_MODES.firstOrNull { it.first == mode }?.second ?: "每次询问"
