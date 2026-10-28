package com.ugport.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import com.ugport.data.model.ThemeMode

/**
 * 把应用级 uiMode 对齐到用户选择的主题，让 values-night 的启动窗口也跟随。
 * 仅 API 31+ 有公开 API；26-30 由 Compose 层自行决定颜色。
 */
fun applyApplicationNightMode(context: Context, mode: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val manager = context.getSystemService(UiModeManager::class.java) ?: return
    val desired = when (ThemeMode.sanitize(mode)) {
        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        else -> UiModeManager.MODE_NIGHT_AUTO
    }
    // 只在用户点选主题时调用：UiModeManager 没有公开的 getter，
    // 放在数据加载时调用会用默认值覆盖上次的选择。
    manager.setApplicationNightMode(desired)
}
