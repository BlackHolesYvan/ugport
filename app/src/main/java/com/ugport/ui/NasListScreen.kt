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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ugport.data.model.AppData
import com.ugport.data.model.NasProfile
import com.ugport.util.UrlTools

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NAS 管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "添加 NAS")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(data.nasList, key = { it.id }) { nas ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(nas.name, style = MaterialTheme.typography.titleMedium)
                        val connectionId = nas.connectionId.ifBlank {
                            UrlTools.parseConnectionId(nas.baseUrl)
                        }
                        val relay = nas.relayDomain ?: UrlTools.parseRelayDomain(nas.baseUrl)
                        Text(
                            "连接标识：$connectionId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "服务器：${relay ?: "登录后自动识别"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (nas.tokenEnc.isNotBlank()) {
                                "登录状态：已保存（长期复用，所有端口共用）"
                            } else {
                                "登录状态：未登录"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "账号：${nas.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { onTest(nas.id) }) { Text("测试登录") }
                            IconButton(onClick = { onEdit(nas.id) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "编辑")
                            }
                            IconButton(onClick = { pendingDelete = nas }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 NAS") },
            text = { Text("将同时删除「${target.name}」下的所有端口快捷方式，确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}
