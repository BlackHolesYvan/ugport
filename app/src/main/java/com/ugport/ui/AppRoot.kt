package com.ugport.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ugport.data.model.ThemeMode
import com.ugport.di.AppContainer
import com.ugport.ui.components.AmbientBackground
import com.ugport.ui.components.BusyOverlay
import com.ugport.ui.components.ConfirmDialog
import com.ugport.ui.components.GlassDialog
import com.ugport.ui.components.PlainField
import com.ugport.ui.theme.UgPortTheme
import com.ugport.ui.web.WebScreen
import com.ugport.ui.theme.applyApplicationNightMode

sealed interface Screen {
    val depth: Int

    data object Home : Screen {
        override val depth = 0
    }

    data object NasList : Screen {
        override val depth = 1
    }

    data class NasEdit(val nasId: String?) : Screen {
        override val depth = 2
    }

    data class ShortcutEdit(val shortcutId: String?, val nasId: String) : Screen {
        override val depth = 2
    }

    data object Settings : Screen {
        override val depth = 1
    }
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
    val view = LocalView.current
    val appContext = LocalContext.current.applicationContext
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (data.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }
    var showExitDialog by remember { mutableStateOf(false) }
    var selectedNasId by rememberSaveable { mutableStateOf("") }

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

    UgPortTheme(darkTheme = darkTheme) {
        // uiMode 在 Manifest 中声明为 configChanges，切换深浅色不会重建 Activity，
        // 因此需要手动同步系统栏图标的明暗。
        SideEffect {
            val window = activity?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }

        LaunchedEffect(viewModel.message) {
            val message = viewModel.message
            if (message != null) {
                snackbarHostState.showSnackbar(message)
                viewModel.message = null
            }
        }

        AmbientBackground {
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
                AnimatedContent(
                    targetState = screen,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val forward = targetState.depth >= initialState.depth
                        val enter = slideInHorizontally(
                            animationSpec = tween(260, easing = FastOutSlowInEasing),
                            initialOffsetX = { if (forward) it / 4 else -it / 4 },
                        ) + fadeIn(tween(200))
                        val exit = slideOutHorizontally(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            targetOffsetX = { if (forward) -it / 8 else it / 8 },
                        ) + fadeOut(tween(160))
                        enter togetherWith exit
                    },
                    label = "screen",
                ) { current ->
                    when (current) {
                        is Screen.Home -> HomeScreen(
                            data = data,
                            busy = viewModel.busy,
                            selectedNasId = selectedNasId.ifBlank { null },
                            onSelectNas = { selectedNasId = it },
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
                            onThemeModeChange = { mode ->
                                applyApplicationNightMode(appContext, mode)
                                viewModel.setThemeMode(mode)
                            },
                            onClearCookies = viewModel::clearCookies,
                            onClearSession = viewModel::clearSession,
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp),
            )

            BusyOverlay(visible = viewModel.busy)

            val otp = viewModel.otpPrompt
            if (otp != null) {
                OtpDialog(
                    onSubmit = viewModel::submitOtp,
                    onDismiss = viewModel::dismissOtp,
                )
            }

            if (showExitDialog) {
                ConfirmDialog(
                    title = "退出应用",
                    message = "确定要退出绿联快连吗？",
                    confirmText = "退出",
                    onConfirm = {
                        showExitDialog = false
                        activity?.finish()
                    },
                    onDismiss = { showExitDialog = false },
                )
            }
        }
    }
}

@Composable
private fun OtpDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    GlassDialog(onDismissRequest = onDismiss) {
        Text(
            text = "二次验证",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "该账号需要输入验证码才能完成登录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        PlainField(
            value = code,
            onValueChange = { code = it },
            label = "验证码",
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            modifier = Modifier.padding(top = 8.dp),
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(
                onClick = { onSubmit(code.trim()) },
                enabled = code.isNotBlank(),
            ) {
                Text("确定")
            }
        }
    }
}
