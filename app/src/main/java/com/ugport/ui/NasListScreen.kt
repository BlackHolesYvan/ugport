package com.ugport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ugport.data.model.AppData
import com.ugport.data.model.NasProfile
import com.ugport.ui.components.ConfirmDialog
import com.ugport.ui.components.EmptyState
import com.ugport.ui.components.PillTone
import com.ugport.ui.components.SectionCard
import com.ugport.ui.components.SectionDivider
import com.ugport.ui.components.SettingRow
import com.ugport.ui.components.StatusPill
import com.ugport.ui.components.UgScaffold
import com.ugport.ui.components.UgTopBar
import com.ugport.ui.theme.AppShape
import com.ugport.util.UrlTools

@Composable
fun NasListScreen(
    data: AppData,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onTest: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<NasProfile?>(null) }

    UgScaffold(
        topBar = {
            UgTopBar(
                title = "NAS 管理",
                onBack = onBack,
                actions = {
                    TextButton(onClick = onAdd) { Text("添加") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                shape = AppShape.pill,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加 NAS")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (data.nasList.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.List,
                        title = "还没有 NAS",
                        message = "添加一台绿联云 NAS，就能在这里管理登录状态与端口",
                        actionText = "添加 NAS",
                        onAction = onAdd,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            items(data.nasList, key = { it.id }) { nas ->
                val connectionId = nas.connectionId.ifBlank { UrlTools.parseConnectionId(nas.baseUrl) }
                val relay = nas.relayDomain ?: UrlTools.parseRelayDomain(nas.baseUrl)
                val loggedIn = nas.tokenEnc.isNotBlank()

                SectionCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .background(
                                        if (loggedIn) {
                                            MaterialTheme.colorScheme.tertiary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        CircleShape,
                                    ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = nas.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            StatusPill(
                                text = if (loggedIn) "已登录" else "未登录",
                                tone = if (loggedIn) PillTone.Success else PillTone.Neutral,
                            )
                        }
                        Text(
                            text = listOfNotNull(
                                connectionId.takeIf { it.isNotBlank() },
                                relay?.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").ifBlank { "尚未识别服务器" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp, start = 17.dp),
                        )
                    }
                    SectionDivider()
                    SettingRow(
                        title = "账号",
                        value = nas.username,
                        valueMonospace = true,
                        onClick = { onEdit(nas.id) },
                        showChevron = true,
                    )
                    SectionDivider()
                    SettingRow(
                        title = "测试登录",
                        subtitle = "重新登录并识别服务器域名",
                        showChevron = true,
                        onClick = { onTest(nas.id) },
                    )
                    SectionDivider()
                    SettingRow(
                        title = "编辑配置",
                        showChevron = true,
                        onClick = { onEdit(nas.id) },
                    )
                    SectionDivider()
                    SettingRow(
                        title = "删除该 NAS",
                        danger = true,
                        onClick = { pendingDelete = nas },
                    )
                }
            }

            item {
                Text(
                    text = "提示：删除 NAS 会同时删除它下面的所有端口快捷方式。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        ConfirmDialog(
            title = "删除 NAS",
            message = "将同时删除「" + target.name + "」下的所有端口快捷方式，确定继续？",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                onDelete(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
