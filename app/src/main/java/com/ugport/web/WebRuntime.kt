package com.ugport.web

/**
 * 端口页的时序与限流常量。
 *
 * 这些值直接决定交互手感与后台保活行为，调整前先看调用点的注释。
 * 单位均为毫秒，除非名字另有说明。
 */
internal const val CONTROLS_AUTO_HIDE_MS = 5_000L

/** 回前台时等待页面 JS 回声的时间。 */
internal const val RENDERER_PROBE_MS = 4_000L

/** 距上次播放上报在这个时间内，视为页面仍然活着，不再探测。 */
internal const val RENDERER_REPORT_WINDOW_MS = 15_000L

/** 渲染进程反复退出时，10 分钟内最多自动重建 [MAX_REBUILDS] 次。 */
internal const val REBUILD_WINDOW_MS = 10 * 60_000L
internal const val MAX_REBUILDS = 3

/** GPU 探测的多次读数，取最后一次成功的结果。 */
internal val GPU_PROBE_READ_DELAYS = longArrayOf(250L, 600L, 1200L, 1800L)

/** 页面加载完成后的空白检测延迟，以及重载后的复检延迟。 */
internal const val BLANK_CHECK_DELAY_MS = 900L
internal const val BLANK_RECHECK_MS = 900L

/** 页面 6 秒后仍无文字时重载一次，缓解动态 chunk 被挂起。 */
internal const val STALL_RELOAD_MS = 6_000L

/** 页面初始化慢时的两次高度复检。 */
internal const val HEIGHT_FOLLOW_UP_MS = 1_200L
internal const val HEIGHT_FOLLOW_UP_RETRY_MS = 2_000L

/** SPA 路由切换后补一次 vh 修复与高度检查。 */
internal const val SPA_VH_FIX_MS = 600L
internal const val SPA_SHORT_CHECK_MS = 800L

/** 原生重授权后等待登录页消失，再决定是否自动填充。 */
internal const val AUTO_FILL_DELAY_MS = 4_000L
internal const val MAX_AUTO_FILLS = 3

/** 页面控制台同一条错误在窗口内只记一次。 */
internal const val CONSOLE_DEDUP_MS = 5_000L

/** 进度条的起点与终点；中间值由 WebChromeClient 上报。 */
internal const val PROGRESS_STARTED = 10
internal const val PROGRESS_FINISHED = 100
