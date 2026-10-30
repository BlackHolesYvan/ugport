package com.ugport.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ugport.R
import com.ugport.data.model.AppData
import com.ugport.data.model.NasProfile
import com.ugport.data.model.Shortcut
import com.ugport.ui.components.AddNasCard
import com.ugport.ui.components.AddTile
import com.ugport.ui.components.EmptyState
import com.ugport.ui.components.NasCard
import com.ugport.ui.components.PortTile
import com.ugport.ui.components.UgScaffold
import com.ugport.ui.components.UgTopBar
import com.ugport.util.UrlTools
import kotlin.math.absoluteValue

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    data: AppData,
    busy: Boolean,
    selectedNasId: String?,
    onSelectNas: (String) -> Unit,
    onOpenShortcut: (Shortcut) -> Unit,
    onEditShortcut: (Shortcut) -> Unit,
    onAddShortcut: (String) -> Unit,
    onEditNas: (String) -> Unit,
    onManageNas: () -> Unit,
    onSettings: () -> Unit,
    onAddNas: () -> Unit,
) {
    val nasList = data.nasList
    val gridState = rememberLazyGridState()
    val initialPage = remember(nasList.size) {
        nasList.indexOfFirst { it.id == selectedNasId }.takeIf { it >= 0 } ?: 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, nasList.size),
        pageCount = { nasList.size + 1 },
    )
    val page = pagerState.currentPage
    val selectedNas = nasList.getOrNull(page)
    val shortcuts = remember(data.shortcuts, selectedNas?.id) {
        data.shortcuts.filter { it.nasId == selectedNas?.id }.sortedBy { it.name }
    }
    val collapsed by remember {
        derivedStateOf { gridState.firstVisibleItemIndex >= 1 }
    }
    var openingId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(busy) {
        if (!busy) openingId = null
    }

    LaunchedEffect(nasList.size) {
        if (pagerState.currentPage > nasList.size) {
            pagerState.scrollToPage(nasList.size.coerceAtLeast(0))
        }
    }
    LaunchedEffect(page) {
        nasList.getOrNull(page)?.let { onSelectNas(it.id) }
    }

    UgScaffold(
        topBar = {
            UgTopBar(
                title = stringResource(R.string.app_name),
                elevated = collapsed,
                actions = {
                    IconButton(onClick = onManageNas) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = "NAS 管理",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (nasList.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Add,
                title = "还没有配置 NAS",
                message = "添加绿联云 NAS 的地址、账号和密码，即可一键打开指定端口",
                actionText = "添加 NAS",
                onAction = onAddNas,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(84.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "nas_pager") {
                    NasPager(
                        pagerState = pagerState,
                        nasList = nasList,
                        onEditNas = onEditNas,
                        onAddNas = onAddNas,
                    )
                }

                if (nasList.size > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "page_dots") {
                        PageDots(count = nasList.size + 1, current = page)
                    }
                }

                if (selectedNas == null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "add_nas_hint") {
                        EmptyState(
                            icon = Icons.Filled.Add,
                            title = "添加一台 NAS",
                            message = "配置 NAS 后，端口会以宫格形式显示在这里",
                            actionText = "添加 NAS",
                            onAction = onAddNas,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    stickyHeader(key = "ports_" + selectedNas.id) {
                        PortSectionHeader(
                            nasName = selectedNas.name,
                            count = shortcuts.size,
                        )
                    }

                    if (shortcuts.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "empty_ports") {
                            Text(
                                text = "这个 NAS 还没有端口，点击下方「添加端口」开始配置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                    } else {
                        items(shortcuts, key = { it.id }) { shortcut ->
                            PortTile(
                                name = shortcut.name,
                                loading = openingId == shortcut.id,
                                enabled = !busy,
                                onClick = {
                                    openingId = shortcut.id
                                    onOpenShortcut(shortcut)
                                },
                                onLongClick = { onEditShortcut(shortcut) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    item(key = "add_tile") {
                        AddTile(enabled = !busy, onClick = { onAddShortcut(selectedNas.id) })
                    }
                    if (shortcuts.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "hint") {
                            Text(
                                text = "轻点打开 · 长按编辑",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NasPager(
    pagerState: PagerState,
    nasList: List<NasProfile>,
    onEditNas: (String) -> Unit,
    onAddNas: () -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        pageSpacing = 10.dp,
    ) { page ->
        val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
            .absoluteValue
            .coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f - 0.05f * offset
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.25f * offset
                },
        ) {
            val nas = nasList.getOrNull(page)
            if (nas != null) {
                NasCard(
                    name = nas.name,
                    connectionId = nas.connectionId.ifBlank { UrlTools.parseConnectionId(nas.baseUrl) },
                    relay = nas.relayDomain ?: UrlTools.parseRelayDomain(nas.baseUrl),
                    loggedIn = nas.tokenEnc.isNotBlank(),
                    selected = page == pagerState.currentPage,
                    onEdit = { onEditNas(nas.id) },
                    onClick = null,
                )
            } else {
                AddNasCard(onClick = onAddNas)
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(
                targetValue = if (selected) 14.dp else 4.dp,
                animationSpec = tween(200),
                label = "dotWidth",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(width = width, height = 4.dp)
                    .background(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
                        },
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}

@Composable
private fun PortSectionHeader(nasName: String, count: Int) {
    val background = MaterialTheme.colorScheme.background
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(background.copy(alpha = 0.96f), background.copy(alpha = 0.90f))
                )
            )
            .padding(top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "端口",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = nasName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count > 0) {
            Text(
                text = count.toString() + " 个端口",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
