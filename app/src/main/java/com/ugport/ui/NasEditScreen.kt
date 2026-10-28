package com.ugport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ugport.data.model.NasProfile
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
    val relayDomain = original.relayDomain ?: UrlTools.parseRelayDomain(original.baseUrl)

    UgScaffold(
        topBar = {
            UgTopBar(
                title = if (isEdit) "编辑 NAS" else "添加 NAS",
                onBack = onBack,
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
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionCard(
                title = "基本信息",
                footer = "只填连接标识，不要填 .ug.link；服务器标识会在登录后自动识别。",
            ) {
                PlainField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称",
                    placeholder = "例如：家里的 NAS",
                    imeAction = ImeAction.Next,
                )
                SectionDivider()
                PlainField(
                    value = connectionId,
                    onValueChange = { connectionId = it },
                    label = "连接标识",
                    placeholder = "my-nas",
                    imeAction = ImeAction.Next,
                )
                if (!relayDomain.isNullOrBlank()) {
                    SectionDivider()
                    SettingRow(
                        title = "已识别服务器",
                        value = relayDomain,
                        valueMonospace = true,
                    )
                }
            }

            SectionCard(title = "账号") {
                PlainField(
                    value = username,
                    onValueChange = { username = it },
                    label = "账号",
                    imeAction = ImeAction.Next,
                )
                SectionDivider()
                PlainField(
                    value = password,
                    onValueChange = { password = it },
                    label = if (isEdit) "密码（留空则不修改）" else "密码",
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        TextButton(
                            onClick = { showPassword = !showPassword },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                text = if (showPassword) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    },
                )
            }

            SectionCard(title = "选项") {
                SwitchRow(
                    title = "自动登录",
                    subtitle = "会话失效时自动用保存的账号密码重新登录",
                    checked = autoLogin,
                    onCheckedChange = { autoLogin = it },
                )
            }

            if (isEdit) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onTest(original.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("测试登录并识别服务器") }
                    Text(
                        text = "提示：修改密码后请先保存，再测试登录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            if (onDelete != null) {
                SectionCard {
                    SettingRow(
                        title = "删除该 NAS",
                        subtitle = "该 NAS 下的所有端口快捷方式也会被删除",
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
            title = "删除 NAS",
            message = "该 NAS 下的所有快捷方式也会被删除，确定继续？",
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
