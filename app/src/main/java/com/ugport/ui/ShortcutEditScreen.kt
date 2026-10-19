package com.ugport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ugport.data.model.NasProfile
import com.ugport.data.model.Shortcut
import com.ugport.util.UrlTools
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
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
        mutableStateOf(
            original.path.ifBlank { UrlTools.parsePath(original.url) }
        )
    }
    var appendUid by remember(shortcut?.id) { mutableStateOf(original.appendUid) }
    var desktopUa by remember(shortcut?.id) { mutableStateOf(original.desktopUa) }
    var confirmDelete by remember { mutableStateOf(false) }

    val portNumber = port.toIntOrNull()
    val canSave = name.isNotBlank() && portNumber != null && portNumber in 1..65535 && nas != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (shortcut == null) "添加端口" else "编辑端口") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "所属 NAS：${nas?.name ?: "未选择"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                placeholder = { Text("例如：Jellyfin") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { input -> port = input.filter(Char::isDigit).take(5) },
                label = { Text("端口号") },
                placeholder = { Text("23003") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("页面路径（可选）") },
                placeholder = { Text("/panel") },
                supportingText = { Text("留空表示根路径 /") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            val connectionId = nas?.let { profile ->
                profile.connectionId.ifBlank { UrlTools.parseConnectionId(profile.baseUrl) }
            }.orEmpty()
            val dockerDomain = nas?.dockerDomain
            if (portNumber != null && portNumber in 1..65535) {
                Text(
                    if (dockerDomain.isNullOrBlank()) {
                        "保存并打开后会自动识别服务器标识"
                    } else {
                        "将打开：https://app-$portNumber-$connectionId.$dockerDomain${UrlTools.normalizePath(path)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("桌面版网页", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "关闭时按移动端页面加载（默认）；开启后使用桌面布局，适合 qBittorrent 等 WebUI",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = desktopUa, onCheckedChange = { desktopUa = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动获取访问凭证", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Docker 端口会先调用 dockerToken 换取一次性登录地址；其他网关端口使用 uid",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = appendUid, onCheckedChange = { appendUid = it })
            }
            if (onDelete != null) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("  删除该端口")
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除端口") },
            text = { Text("确定删除「${original.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
