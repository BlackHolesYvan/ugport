package com.ugport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ugport.data.model.NasProfile
import com.ugport.util.UrlTools
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NasEditScreen(
    nas: NasProfile?,
    onBack: () -> Unit,
    onSave: (NasProfile, String?) -> Unit,
    onDelete: (() -> Unit)?,
    onTest: (String) -> Unit,
) {
    val original = remember(nas?.id) {
        nas ?: NasProfile(
            id = UUID.randomUUID().toString(),
            name = "",
            connectionId = "",
            username = "",
        )
    }
    var name by remember(nas?.id) { mutableStateOf(original.name) }
    var connectionId by remember(nas?.id) {
        mutableStateOf(
            original.connectionId.ifBlank { UrlTools.parseConnectionId(original.baseUrl) }
        )
    }
    var username by remember(nas?.id) { mutableStateOf(original.username) }
    var password by remember(nas?.id) { mutableStateOf("") }
    var autoLogin by remember(nas?.id) { mutableStateOf(original.autoLogin) }
    var showPassword by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val isEdit = nas != null
    val canSave = name.isNotBlank() && connectionId.isNotBlank() && username.isNotBlank() &&
        (isEdit || password.isNotBlank())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "编辑 NAS" else "添加 NAS") },
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
                                    connectionId = UrlTools.normalizeConnectionId(connectionId),
                                    baseUrl = "",
                                    username = username.trim(),
                                    autoLogin = autoLogin,
                                ),
                                password.takeIf { it.isNotBlank() },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                placeholder = { Text("例如：家里的 NAS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = connectionId,
                onValueChange = { connectionId = it },
                label = { Text("连接标识") },
                placeholder = { Text("my-nas") },
                supportingText = {
                    Text("只填连接标识，不要填 .ug.link；服务器标识登录后自动识别")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val relayDomain = original.relayDomain ?: UrlTools.parseRelayDomain(original.baseUrl)
            if (!relayDomain.isNullOrBlank()) {
                Text(
                    "已识别服务器：$relayDomain",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (isEdit) "密码（留空则不修改）" else "密码") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "隐藏" else "显示")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动登录", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "会话失效时自动用保存的账号密码重新登录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoLogin, onCheckedChange = { autoLogin = it })
                }
            }

            if (isEdit) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onTest(original.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("测试登录并识别服务器") }
                Text(
                    "提示：修改密码后请先保存，再测试登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (onDelete != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("  删除该 NAS")
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 NAS") },
            text = { Text("该 NAS 下的所有快捷方式也会被删除，确定继续？") },
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
