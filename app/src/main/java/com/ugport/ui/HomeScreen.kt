package com.ugport.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ugport.data.model.AppData
import com.ugport.data.model.NasProfile
import com.ugport.data.model.Shortcut
import com.ugport.util.UrlTools

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    data: AppData,
    onOpenShortcut: (Shortcut) -> Unit,
    onEditShortcut: (Shortcut) -> Unit,
    onAddShortcut: (String) -> Unit,
    onEditNas: (String) -> Unit,
    onManageNas: () -> Unit,
    onSettings: () -> Unit,
    onAddNas: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("绿联快连") },
                actions = {
                    IconButton(onClick = onManageNas) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "NAS 管理")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = if (data.nasList.isEmpty()) onAddNas else onManageNas) {
                Icon(Icons.Filled.Add, contentDescription = "添加")
            }
        },
    ) { padding ->
        if (data.nasList.isEmpty()) {
            EmptyState(onAddNas, Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                data.nasList.forEach { nas ->
                    item(key = "nas_${nas.id}") {
                        NasHeader(
                            nas = nas,
                            onAddShortcut = { onAddShortcut(nas.id) },
                            onEditNas = { onEditNas(nas.id) },
                        )
                    }
                    val shortcuts = data.shortcuts
                        .filter { it.nasId == nas.id }
                        .sortedBy { it.name }
                    if (shortcuts.isEmpty()) {
                        item(key = "empty_${nas.id}") {
                            Text(
                                text = "暂无端口快捷方式，点击右上角「+ 端口」添加",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                            )
                        }
                    } else {
                        items(shortcuts, key = { it.id }) { shortcut ->
                            ShortcutCard(
                                shortcut = shortcut,
                                onClick = { onOpenShortcut(shortcut) },
                                onEdit = { onEditShortcut(shortcut) },
                            )
                        }
                    }
                    item(key = "space_${nas.id}") { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun NasHeader(nas: NasProfile, onAddShortcut: () -> Unit, onEditNas: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val connectionId = nas.connectionId.ifBlank { UrlTools.parseConnectionId(nas.baseUrl) }
            val relay = nas.relayDomain ?: UrlTools.parseRelayDomain(nas.baseUrl)
            Text(nas.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = listOfNotNull(connectionId, relay).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onAddShortcut) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("端口")
        }
        IconButton(onClick = onEditNas) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑 NAS")
        }
    }
}

@Composable
private fun ShortcutCard(shortcut: Shortcut, onClick: () -> Unit, onEdit: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val port = shortcut.port.takeIf { it > 0 } ?: UrlTools.extractAppPort(shortcut.url)
                val path = shortcut.path.ifBlank { UrlTools.parsePath(shortcut.url) }
                Text(shortcut.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (port != null && port > 0) {
                        "端口 $port" + if (path.isNotBlank()) " · $path" else ""
                    } else {
                        shortcut.url
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑快捷方式")
            }
        }
    }
}

@Composable
private fun EmptyState(onAddNas: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有配置 NAS", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "添加绿联云 NAS 地址、账号和密码，即可一键打开指定端口",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAddNas) { Text("添加 NAS") }
        }
    }
}
