package com.ugport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ugport.data.model.NasProfile
import com.ugport.data.model.Shortcut
import com.ugport.ui.components.ConfirmDialog
import com.ugport.ui.components.PlainField
import com.ugport.ui.components.SectionCard
import com.ugport.ui.components.SectionDivider
import com.ugport.ui.components.SettingRow
import com.ugport.ui.components.SwitchRow
import com.ugport.ui.components.UgScaffold
import com.ugport.ui.components.UgTopBar
import com.ugport.util.UrlTools
import java.util.UUID

@Composable
fun ShortcutEditScreen(
    nas: NasProfile?,
    shortcut: Shortcut?,
    onBack: () -> Unit,
    onSave: (Shortcut) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val original = remember(shortcut?.id) {
        shortcut ?: Shortcut(
            id = UUID.randomUUID().toString(),
            nasId = nas?.id.orEmpty(),
            name = "",
            port = 0,
            path = "",
            url = "",
            appendUid = true,
            desktopUa = false,
            allowBackground = false,
        )
    }
    var name by remember(shortcut?.id) { mutableStateOf(original.name) }
    var port by remember(shortcut?.id) {
        mutableStateOf(
            (original.port.takeIf { it > 0 } ?: UrlTools.extractAppPort(original.url) ?: 0)
                .takeIf { it > 0 }?.toString().orEmpty()
        )
    }
    var path by remember(shortcut?.id) {
        mutableStateOf(original.path.ifBlank { UrlTools.parsePath(original.url) })
    }
    var appendUid by remember(shortcut?.id) { mutableStateOf(original.appendUid) }
    var desktopUa by remember(shortcut?.id) { mutableStateOf(original.desktopUa) }
    var allowBackground by remember(shortcut?.id) { mutableStateOf(original.allowBackground) }
    var confirmDelete by remember { mutableStateOf(false) }

    val portNumber = port.toIntOrNull()
    val canSave = name.isNotBlank() && portNumber != null && portNumber in 1..65535 && nas != null
    val connectionId = nas?.let { profile ->
        profile.connectionId.ifBlank { UrlTools.parseConnectionId(profile.baseUrl) }
    }.orEmpty()
    val dockerDomain = nas?.dockerDomain
    val previewUrl = if (portNumber != null && portNumber in 1..65535 && !dockerDomain.isNullOrBlank()) {
        "https://app-" + portNumber + "-" + connectionId + "." + dockerDomain + UrlTools.normalizePath(path)
    } else {
        null
    }

    UgScaffold(
        topBar = {
            UgTopBar(
                title = if (shortcut == null) "添加端口" else "编辑端口",
                onBack = onBack,
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                original.copy(
                                    name = name.trim(),
                                    port = portNumber ?: 0,
                                    path = UrlTools.normalizePath(path),
                                    url = "",
                                    appendUid = appendUid,
                                    desktopUa = desktopUa,
                                    allowBackground = allowBackground,
                                )
                            )
                        },
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionCard(title = "所属 NAS") {
                SettingRow(
                    title = "NAS",
                    value = nas?.name ?: "未选择",
                    valueMonospace = false,
                )
            }

            SectionCard(
                title = "基本信息",
                footer = "端口号决定访问地址，页面路径留空表示根路径 /。",
            ) {
                PlainField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称",
                    placeholder = "例如：Jellyfin",
                    imeAction = ImeAction.Next,
                )
                SectionDivider()
                PlainField(
                    value = port,
                    onValueChange = { input -> port = input.filter(Char::isDigit).take(5) },
                    label = "端口号",
                    placeholder = "23003",
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                )
                SectionDivider()
                PlainField(
                    value = path,
                    onValueChange = { path = it },
                    label = "页面路径（可选）",
                    placeholder = "/panel",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                )
            }

            SectionCard(title = "打开地址") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = previewUrl ?: "保存并打开后会自动识别服务器标识",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (previewUrl != null) FontFamily.Monospace else FontFamily.Default,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SectionCard(
                title = "打开方式",
                footer = "开启后台播放后，小米/红米还需在系统设置中允许自启动，把省电策略设为无限制，并在最近任务界面下拉应用卡片锁定后台。",
            ) {
                SwitchRow(
                    title = "桌面版网页",
                    subtitle = "使用桌面布局，适合 qBittorrent 等 WebUI",
                    checked = desktopUa,
                    onCheckedChange = { desktopUa = it },
                )
                SectionDivider()
                SwitchRow(
                    title = "允许后台播放",
                    subtitle = "锁屏或切换应用后继续播放，并常驻通知栏媒体控制",
                    checked = allowBackground,
                    onCheckedChange = { allowBackground = it },
                )
                SectionDivider()
                SwitchRow(
                    title = "自动获取访问凭证",
                    subtitle = "Docker 端口先换取一次性登录地址，其他网关端口使用 uid",
                    checked = appendUid,
                    onCheckedChange = { appendUid = it },
                )
            }

            if (onDelete != null) {
                SectionCard {
                    SettingRow(
                        title = "删除该端口",
                        subtitle = "只会移除这条快捷方式，不影响 NAS 配置",
                        danger = true,
                        showChevron = true,
                        onClick = { confirmDelete = true },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "删除端口",
            message = "确定删除「" + original.name + "」？",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
