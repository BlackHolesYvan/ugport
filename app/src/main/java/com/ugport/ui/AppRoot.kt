package com.ugport.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ugport.di.AppContainer
import com.ugport.ui.theme.UgPortTheme

sealed interface Screen {
    data object Home : Screen
    data object NasList : Screen
    data class NasEdit(val nasId: String?) : Screen
    data class ShortcutEdit(val shortcutId: String?, val nasId: String) : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot(container: AppContainer) {
    val viewModel: MainViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    )
    val data by viewModel.data.collectAsStateWithLifecycle()
    val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val screen = backStack.last()
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = LocalContext.current as? Activity
    var showExitDialog by remember { mutableStateOf(false) }

    fun navigate(next: Screen) {
        backStack.add(next)
    }

    fun goBack() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    // WebView 内部有自己的返回处理；这里只处理配置页面的返回
    BackHandler(enabled = viewModel.webTarget == null) {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        } else {
            showExitDialog = true
        }
    }

    UgPortTheme {
        LaunchedEffect(viewModel.message) {
            val message = viewModel.message
            if (message != null) {
                snackbarHostState.showSnackbar(message)
                viewModel.message = null
            }
        }

        Box(Modifier.fillMaxSize()) {
            val web = viewModel.webTarget
            if (web != null) {
                WebScreen(
                    viewModel = viewModel,
                    target = web,
                    cookieJar = container.cookieJar,
                    externalLinkMode = data.externalLinkMode,
                    onExternalLinkModeChange = viewModel::setExternalLinkMode,
                    playback = container.playback,
                    onBack = { viewModel.closeWeb() },
                )
            } else {
                when (val current = screen) {
                    is Screen.Home -> HomeScreen(
                        data = data,
                        onOpenShortcut = viewModel::openShortcut,
                        onEditShortcut = { navigate(Screen.ShortcutEdit(it.id, it.nasId)) },
                        onAddShortcut = { navigate(Screen.ShortcutEdit(null, it)) },
                        onEditNas = { navigate(Screen.NasEdit(it)) },
                        onManageNas = { navigate(Screen.NasList) },
                        onSettings = { navigate(Screen.Settings) },
                        onAddNas = { navigate(Screen.NasEdit(null)) },
                    )
                    is Screen.NasList -> NasListScreen(
                        data = data,
                        onBack = { goBack() },
                        onAdd = { navigate(Screen.NasEdit(null)) },
                        onEdit = { navigate(Screen.NasEdit(it)) },
                        onTest = viewModel::testLogin,
                        onDelete = { id -> viewModel.deleteNas(id) { } },
                    )
                    is Screen.NasEdit -> NasEditScreen(
                        nas = current.nasId?.let { data.nasList.firstOrNull { nas -> nas.id == it } },
                        onBack = { goBack() },
                        onSave = { profile, password ->
                            viewModel.saveNas(profile, password) { goBack() }
                        },
                        onDelete = current.nasId?.let { id ->
                            {
                                viewModel.deleteNas(id) { goBack() }
                            }
                        },
                        onTest = viewModel::testLogin,
                    )
                    is Screen.ShortcutEdit -> ShortcutEditScreen(
                        nas = data.nasList.firstOrNull { it.id == current.nasId },
                        shortcut = current.shortcutId?.let { id ->
                            data.shortcuts.firstOrNull { it.id == id }
                        },
                        onBack = { goBack() },
                        onSave = { shortcut ->
                            viewModel.saveShortcut(shortcut) { goBack() }
                        },
                        onDelete = current.shortcutId?.let { id ->
                            {
                                viewModel.deleteShortcut(id) { goBack() }
                            }
                        },
                    )
                    is Screen.Settings -> SettingsScreen(
                        data = data,
                        diagnostics = viewModel.diagnostics,
                        onBack = { goBack() },
                        onExternalLinkModeChange = viewModel::setExternalLinkMode,
                        onClearCookies = viewModel::clearCookies,
                        onClearSession = viewModel::clearSession,
                    )
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )

            if (viewModel.busy) {
                BusyOverlay()
            }

            val otp = viewModel.otpPrompt
            if (otp != null) {
                OtpDialog(
                    onSubmit = viewModel::submitOtp,
                    onDismiss = viewModel::dismissOtp,
                )
            }

            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("退出应用") },
                    text = { Text("确定要退出绿联快连吗？") },
                    confirmButton = {
                        TextButton(onClick = {
                            showExitDialog = false
                            activity?.finish()
                        }) { Text("退出") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitDialog = false }) { Text("取消") }
                    },
                )
            }
        }
    }
}

@Composable
private fun BusyOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.width(16.dp))
                Text("正在登录…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun OtpDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("二次验证") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("验证码") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code.trim()) }, enabled = code.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
