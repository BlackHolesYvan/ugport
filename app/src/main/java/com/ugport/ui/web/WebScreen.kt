package com.ugport.ui.web

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import com.ugport.media.PlaybackHub
import com.ugport.media.WebMediaController
import com.ugport.network.HttpFactory
import com.ugport.ui.MainViewModel
import com.ugport.ui.WebTarget
import com.ugport.ui.components.ConfirmDialog
import com.ugport.ui.components.GlassActionSheet
import com.ugport.ui.components.SheetAction
import com.ugport.ui.components.WebGpuHint
import com.ugport.util.BatteryOptimization
import com.ugport.util.UrlTools
import com.ugport.web.AUTO_FILL_DELAY_MS
import com.ugport.web.BLANK_CHECK_DELAY_MS
import com.ugport.web.BLANK_CHECK_SCRIPT
import com.ugport.web.BLANK_RECHECK_MS
import com.ugport.web.CONTROLS_AUTO_HIDE_MS
import com.ugport.web.CONSOLE_DEDUP_MS
import com.ugport.web.GPU_PROBE_READ_DELAYS
import com.ugport.web.HEIGHT_FOLLOW_UP_MS
import com.ugport.web.HEIGHT_FOLLOW_UP_RETRY_MS
import com.ugport.web.MAX_AUTO_FILLS
import com.ugport.web.MAX_REBUILDS
import com.ugport.web.MEDIA_PAUSE_SCRIPT
import com.ugport.web.PAGE_INFO_SCRIPT
import com.ugport.web.PROGRESS_FINISHED
import com.ugport.web.PROGRESS_STARTED
import com.ugport.web.REBUILD_WINDOW_MS
import com.ugport.web.RENDERER_PROBE_MS
import com.ugport.web.RENDERER_REPORT_WINDOW_MS
import com.ugport.web.SHORT_PAGE_SCRIPT
import com.ugport.web.SPA_SHORT_CHECK_MS
import com.ugport.web.SPA_VH_FIX_MS
import com.ugport.web.STALL_RELOAD_MS
import com.ugport.web.STALL_SCRIPT
import com.ugport.web.VH_FIX_SCRIPT
import com.ugport.web.LoginScripts
import com.ugport.web.MediaHookScript
import com.ugport.web.GpuAdapterState
import com.ugport.web.WebBridge
import com.ugport.web.WebFullscreenHost
import com.ugport.web.WebGpuProbe
import com.ugport.web.WebGpuProbeResult
import com.ugport.web.parseWebGpuProbe
import com.ugport.web.webGpuHintText
import com.ugport.web.webGpuVerdict
import com.ugport.web.WebMediaBridge
import com.ugport.web.WebViewConfigurator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(
    viewModel: MainViewModel,
    target: WebTarget,
    cookieJar: HttpFactory.MemoryCookieJar,
    externalLinkMode: String,
    onExternalLinkModeChange: (String) -> Unit,
    playback: PlaybackHub,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current ?: context.findActivity()
    val fullscreenHost = remember(activity, viewModel.diagnostics) {
        WebFullscreenHost(activity, viewModel.diagnostics)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentTarget = rememberUpdatedState(target)
    val desktopUa = target.shortcut.desktopUa
    // 端口级开关：关闭后不注入媒体脚本、不建媒体桥、不启动前台服务
    val mediaEnabled = target.shortcut.allowBackground
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 只用于 dispose 时精确解绑，避免误清新页面的控制器
    val mediaControllerHolder = remember { arrayOfNulls<WebMediaController>(1) }
    var progress by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var lastAutoFill by remember { mutableStateOf<String?>(null) }
    var lastExtract by remember { mutableStateOf<String?>(null) }
    var autoFillCount by remember { mutableIntStateOf(0) }
    // 同一 URL 只做一次「清缓存重载」，避免空白页无限循环
    var blankRetriedUrl by remember { mutableStateOf<String?>(null) }
    // 页面骨架加载完成但一直没有文字时，自动重载一次。
    // 并发 chunk 请求可能被代理挂起，导致脚本永远不执行。
    var stallRetriedUrl by remember { mutableStateOf<String?>(null) }
    // 外部链接：待用户选择的目标，以及选择「应用内」后放行一次的 URL
    var pendingLink by remember { mutableStateOf<Uri?>(null) }
    var pendingInternal by remember { mutableStateOf<String?>(null) }
    var rememberChoice by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    // 控制台错误去重：第三方页面常连续输出同一条
    var lastConsoleKey by remember { mutableStateOf("") }
    var lastConsoleAt by remember { mutableStateOf(0L) }
    // WebGPU 能力探针：token 标识页面代次，避免重定向后的旧回调覆盖新页面
    var gpuPageToken by remember { mutableIntStateOf(0) }
    var gpuProbedToken by remember { mutableIntStateOf(-1) }
    var gpuProbe by remember { mutableStateOf<WebGpuProbeResult?>(null) }
    var gpuDismissed by remember { mutableStateOf(false) }
    var gpuLogSignature by remember { mutableStateOf("") }
    // 右上角刷新/主页按钮 5 秒无操作后收起为右侧条状
    var controlsExpanded by remember { mutableStateOf(true) }
    var controlsReset by remember { mutableIntStateOf(0) }
    // 渲染进程退出后的恢复：webGeneration 变化会让 AndroidView 重新创建 WebView
    var webGeneration by remember { mutableIntStateOf(0) }
    var rendererDead by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    var showRendererHint by remember { mutableStateOf(false) }
    var disposed by remember { mutableStateOf(false) }
    var pendingRestoreUrl by remember { mutableStateOf<String?>(null) }
    val rebuildStamps = remember { ArrayDeque<Long>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val webViewVersion = remember(context) {
        WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: "?"
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileCallback
        fileCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    fun maybePromptBatteryOptimization() {
        if (!mediaEnabled) return
        if (BatteryOptimization.isIgnoring(context)) return
        val prefs = context.getSharedPreferences("ugport_webview", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompted", false)) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        prefs.edit().putBoolean("battery_prompted", true).apply()
        showBatteryDialog = true
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.diagnostics.detail("web: notification permission granted=$granted")
        maybePromptBatteryOptimization()
    }

    fun requestNotificationPermissionIfNeeded() {
        if (!mediaEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            maybePromptBatteryOptimization()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            maybePromptBatteryOptimization()
            return
        }
        val prefs = context.getSharedPreferences("ugport_webview", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notification_asked", false)) {
            maybePromptBatteryOptimization()
            return
        }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        prefs.edit().putBoolean("notification_asked", true).apply()
        runCatching {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }.onFailure {
            viewModel.diagnostics.log("web: notification permission launch failed")
            maybePromptBatteryOptimization()
        }
    }

    fun fillCredentials() {
        val current = currentTarget.value
        val credentials = viewModel.credentialsFor(current.nasId) ?: return
        webView?.evaluateJavascript(
            LoginScripts.autoFill(credentials.first, credentials.second),
            null,
        )
    }

    /**
     * 空白页兜底：默认允许缓存，若命中旧 HTML/JS 导致页面完全空白，
     * 连续两次检测都无可见内容时清空 WebView 缓存并重载一次。
     */
    fun scheduleBlankCheck(view: WebView, url: String) {
        if (blankRetriedUrl == url) return
        view.postDelayed({
            if (view !== webView) return@postDelayed
            if (playback.isPlaying) return@postDelayed
            if (view.url == url && blankRetriedUrl != url) {
                view.evaluateJavascript(BLANK_CHECK_SCRIPT) { result ->
                    val blank = result?.trim('"') == "blank"
                    if (blank) {
                        viewModel.diagnostics.log("web: blank check=true")
                        viewModel.diagnostics.detail(
                            "web: blank url=" + viewModel.diagnostics.redactUrl(url)
                        )
                        // 给前端留出初始化时间，二次确认后再动缓存
                        view.postDelayed({
                            if (view !== webView) return@postDelayed
                            if (playback.isPlaying) return@postDelayed
                            if (view.url == url && blankRetriedUrl != url) {
                                view.evaluateJavascript(BLANK_CHECK_SCRIPT) { second ->
                                    val stillBlank = second?.trim('"') == "blank"
                                    viewModel.diagnostics.detail("web: blank recheck=$stillBlank")
                                    if (stillBlank) {
                                        blankRetriedUrl = url
                                        viewModel.diagnostics.log(
                                            "web: blank detected, clear cache and reload"
                                        )
                                        view.clearCache(true)
                                        view.reload()
                                    }
                                }
                            }
                        }, BLANK_RECHECK_MS)
                    }
                }
            }
        }, BLANK_CHECK_DELAY_MS)
    }

    /** 页面 6 秒后仍然没有任何文字时重载一次，缓解动态 chunk 请求被连接挂起的问题。 */
    fun scheduleStallReload(view: WebView, url: String) {
        if (stallRetriedUrl == url) return
        view.postDelayed({
            if (view !== webView) return@postDelayed
            if (playback.isPlaying) return@postDelayed
            if (view.url == url && stallRetriedUrl != url) {
                view.evaluateJavascript(STALL_SCRIPT) { result ->
                    if (result?.trim('"') == "stall") {
                        stallRetriedUrl = url
                        viewModel.diagnostics.log("web: page stalled (no text), reload once")
                        view.reload()
                    }
                }
            }
        }, STALL_RELOAD_MS)
    }

    /**
     * 页面初始化较慢时（如 qBittorrent 要先跑完一遍 JS 才建出面板），
     * 1.2s 后再查一次高度；仍然不足就再补诊断并重试一次修复。
     */
    fun scheduleHeightFollowUp(view: WebView, url: String, attempt: Int = 0) {
        if (attempt >= 2) return
        view.postDelayed({
            if (view !== webView || view.url != url) return@postDelayed
            view.evaluateJavascript(SHORT_PAGE_SCRIPT) { result ->
                if (result?.trim('"') == "short") {
                    view.evaluateJavascript(PAGE_INFO_SCRIPT) { info ->
                        viewModel.diagnostics.detail("web page+: " + viewModel.diagnostics.redactUrl(info.orEmpty()))
                    }
                    scheduleHeightFollowUp(view, url, attempt + 1)
                }
            }
        }, if (attempt == 0) HEIGHT_FOLLOW_UP_MS else HEIGHT_FOLLOW_UP_RETRY_MS)
    }

    fun applyGpuProbe(result: WebGpuProbeResult) {
        gpuProbe = result
        val verdict = webGpuVerdict(result)
        // pending 只更新状态，等最终结果再落日志，避免一次页面出现两行
        if (result.adapter == GpuAdapterState.PENDING) return
        val signature = buildString {
            append(verdict.name).append('|')
            append(result.secure).append('|')
            append(result.protocol).append('|')
            append(result.gpu).append('|')
            append(result.adapter.name).append('|')
            append(result.webgl2).append('|')
            append(result.visible).append('|')
            append(webViewVersion)
        }
        if (signature != gpuLogSignature) {
            gpuLogSignature = signature
            viewModel.diagnostics.log(
                "webgpu: verdict=${verdict.name} secure=${result.secure} " +
                    "protocol=${result.protocol} gpu=${result.gpu} " +
                    "adapter=${result.adapter.name} webgl2=${result.webgl2} " +
                    "visible=${result.visible} webview=$webViewVersion"
            )
        }
        result.error?.takeIf { it.isNotBlank() }?.let { error ->
            viewModel.diagnostics.detail(
                "webgpu error: " + error.replace('\n', ' ').take(80)
            )
        }
    }

    fun readGpuProbe(view: WebView, token: Int, step: Int) {
        val delay = GPU_PROBE_READ_DELAYS.getOrNull(step) ?: return
        view.postDelayed({
            if (token != gpuPageToken || webView !== view) return@postDelayed
            view.evaluateJavascript(WebGpuProbe.readScript()) { raw ->
                if (token != gpuPageToken || webView !== view) return@evaluateJavascript
                val result = parseWebGpuProbe(raw) ?: return@evaluateJavascript
                applyGpuProbe(result)
                if (result.adapter == GpuAdapterState.PENDING) {
                    readGpuProbe(view, token, step + 1)
                }
            }
        }, delay)
    }

    fun probeWebGpu(view: WebView, url: String, token: Int) {
        if (token != gpuPageToken || gpuProbedToken == token) return
        if (errorText != null) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        gpuProbedToken = token
        view.evaluateJavascript(WebGpuProbe.startScript()) { raw ->
            if (token != gpuPageToken || webView !== view) return@evaluateJavascript
            val result = parseWebGpuProbe(raw) ?: return@evaluateJavascript
            applyGpuProbe(result)
            if (result.gpu == true && result.adapter == GpuAdapterState.PENDING) {
                readGpuProbe(view, token, 0)
            }
        }
    }

    fun markRendererHintShown() {
        context.getSharedPreferences("ugport_webview", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("renderer_hint_shown", true)
            .apply()
    }

    /** 渲染进程退出且电池优化已放行时，提示用户去系统里「锁定后台」。 */
    fun showRendererHintIfNeeded() {
        if (!BatteryOptimization.isIgnoring(context)) return
        val prefs = context.getSharedPreferences("ugport_webview", Context.MODE_PRIVATE)
        if (prefs.getBoolean("renderer_hint_shown", false)) return
        showRendererHint = true
    }

    /**
     * 销毁已失效的 WebView 并让 Compose 重新创建实例。
     * 只在渲染进程退出/无响应时调用；不做 detach（那会清掉续播位置）。
     */
    fun rebuildWebView(reason: String) {
        if (disposed) return
        if (activity?.isDestroyed == true) return
        if (lifecycle.currentState == Lifecycle.State.DESTROYED) return
        val now = SystemClock.elapsedRealtime()
        while (rebuildStamps.isNotEmpty() && now - rebuildStamps.first() > REBUILD_WINDOW_MS) {
            rebuildStamps.removeFirst()
        }
        if (reason != "retry" && rebuildStamps.size >= MAX_REBUILDS) {
            rendererDead = true
            recovering = false
            errorText = "页面渲染进程反复退出，请返回重试"
            viewModel.diagnostics.log("web: rebuild limit reached ($reason)")
            return
        }
        if (reason == "retry") rebuildStamps.clear()
        rebuildStamps.addLast(now)

        val old = webView
        // 一次性鉴权地址不能重放，否则会 400；其余地址优先原样恢复
        pendingRestoreUrl = runCatching { old?.url }.getOrNull()
            ?.takeIf {
                (it.startsWith("http://") || it.startsWith("https://")) &&
                    !it.contains("/api/ugreen/auth")
            }
        webView = null
        mediaControllerHolder[0]?.let { playback.release(it) }
        mediaControllerHolder[0] = null
        if (old != null) {
            // 官方要求先移出视图树再 destroy，否则可能泄漏
            runCatching { (old.parent as? ViewGroup)?.removeView(old) }
            runCatching { old.stopLoading() }
            runCatching { old.destroy() }
        }
        rendererDead = false
        recovering = true
        errorText = null
        progress = PROGRESS_STARTED
        webGeneration++
        viewModel.diagnostics.log("web: rebuild ($reason)")
    }

    /** 回前台时用一次 JS 回声确认渲染进程是否还活着。 */
    fun probeRendererAlive(view: WebView) {
        if (view !== webView) return
        val loaded = runCatching { view.url }.getOrNull()
        if (loaded.isNullOrEmpty() || !loaded.startsWith("http")) return
        var answered = false
        val sent = runCatching {
            view.evaluateJavascript("1") { answered = true }
            true
        }.getOrDefault(false)
        if (!sent) {
            mainHandler.post { rebuildWebView("eval failed") }
            return
        }
        mainHandler.postDelayed({
            if (answered || view !== webView) return@postDelayed
            viewModel.diagnostics.log("web: renderer unresponsive")
            rebuildWebView("unresponsive")
        }, RENDERER_PROBE_MS)
    }

    LaunchedEffect(controlsExpanded, controlsReset) {
        if (!controlsExpanded) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsExpanded = false
    }

    BackHandler {
        if (fullscreenHost.isActive) {
            fullscreenHost.hide("back")
            return@BackHandler
        }
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 渲染进程退出后 webGeneration 变化，整块 AndroidView 重新创建
        key(webGeneration) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            WebViewConfigurator.configure(this, desktopUa, mediaEnabled)
                            if (mediaEnabled) {
                                // 允许后台播放的端口常驻高优先级。
                                // 切章瞬间 hasMedia 会短暂为 false，不能据此降级。
                                // 一旦掉回 BOUND，渲染进程在后台更容易被系统回收
                                runCatching {
                                    setRendererPriorityPolicy(
                                        WebView.RENDERER_PRIORITY_IMPORTANT,
                                        false,
                                    )
                                }
                            }
                            addJavascriptInterface(
                                WebBridge { token ->
                                    val current = currentTarget.value
                                    viewModel.onWebToken(current.nasId, token, current.shortcut)
                                },
                                "UgBridge",
                            )
                            if (mediaEnabled) {
                                val mediaController = object : WebMediaController {
                                    private var released = false

                                    override fun release() {
                                        released = true
                                    }

                                    override fun evaluate(script: String) {
                                        if (released) return
                                        post {
                                            if (released) return@post
                                            runCatching { evaluateJavascript(script, null) }
                                        }
                                    }

                                    override fun isTrustedPage(): Boolean {
                                        val baseUrl = viewModel.repo.nas(currentTarget.value.nasId)?.baseUrl
                                        val host = runCatching { Uri.parse(url) }.getOrNull()?.host
                                        return WebViewConfigurator.isTrustedHost(host, baseUrl)
                                    }
                                }
                                mediaControllerHolder[0] = mediaController
                                playback.attach(mediaController)
                                addJavascriptInterface(
                                    WebMediaBridge { json -> playback.onJsState(json) },
                                    "UgMedia",
                                )
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    if (view !== webView) return
                                    fullscreenHost.hide("page start")
                                    gpuPageToken++
                                    gpuProbe = null
                                    if (mediaEnabled) playback.onPageStarted()
                                    progress = PROGRESS_STARTED
                                    if (url != null) {
                                        if (url == pendingInternal) pendingInternal = null
                                        viewModel.diagnostics.log("web: start")
                                        viewModel.diagnostics.detail(
                                            "web: start url=" + viewModel.diagnostics.redactUrl(url)
                                        )
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (view == null || url == null || view !== webView) return
                                    progress = PROGRESS_FINISHED
                                    if (view.url == url) {
                                        rendererDead = false
                                        recovering = false
                                        view.evaluateJavascript(PAGE_INFO_SCRIPT) { result ->
                                            viewModel.diagnostics.detail(
                                                "web page: ${viewModel.diagnostics.redactUrl(result.orEmpty())}"
                                            )
                                        }
                                        if (mediaEnabled) {
                                            playback.onPageLoaded()
                                            view.post {
                                                if (view !== webView) return@post
                                                view.evaluateJavascript(
                                                    "window.__ugMediaVersion === ${MediaHookScript.VERSION}",
                                                ) { result ->
                                                    viewModel.diagnostics.detail(
                                                        "web: media hook=" + (result?.trim('"') ?: "?")
                                                    )
                                                }
                                            }
                                        }
                                        scheduleBlankCheck(view, url)
                                        scheduleHeightFollowUp(view, url)
                                        scheduleStallReload(view, url)
                                        probeWebGpu(view, url, gpuPageToken)
                                    }
                                    val current = currentTarget.value
                                    val nas = viewModel.repo.nas(current.nasId)
                                    val baseUrl = nas?.baseUrl
                                    if (!WebViewConfigurator.isTrustedHost(Uri.parse(url).host, baseUrl)) return
                                    val relayHost = nas?.let { UrlTools.loginHost(it) }

                                    if (UrlTools.isUgreenLoginUrl(url, relayHost)) {
                                        // 先尝试原生重新授权；失败或需要 OTP 时再由页面处理
                                        viewModel.diagnostics.log("web: ugos login page detected")
                                        viewModel.diagnostics.detail(
                                            "web: login url=" + viewModel.diagnostics.redactUrl(url)
                                        )
                                        viewModel.onLoginPageDetected(currentTarget.value)
                                        if (lastAutoFill != url && autoFillCount < MAX_AUTO_FILLS) {
                                            lastAutoFill = url
                                            autoFillCount++
                                            // 原生重授权通常 1-2 秒完成；
                                            // 等待 AUTO_FILL_DELAY_MS 后仍停在登录页再自动填充
                                            view.postDelayed({
                                                if (view !== webView) return@postDelayed
                                                if (!viewModel.busy && viewModel.otpPrompt == null &&
                                                    UrlTools.isUgreenLoginUrl(view.url, relayHost)
                                                ) {
                                                    fillCredentials()
                                                }
                                            }, AUTO_FILL_DELAY_MS)
                                            view.postDelayed({
                                                if (view !== webView) return@postDelayed
                                                if (!viewModel.busy && viewModel.otpPrompt == null &&
                                                    UrlTools.isUgreenLoginUrl(view.url, relayHost)
                                                ) {
                                                    fillCredentials()
                                                }
                                            }, 8000L)
                                            view.evaluateJavascript(LoginScripts.tokenWatcher(), null)
                                        }
                                    } else if (lastExtract != url) {
                                        lastExtract = url
                                        view.evaluateJavascript(LoginScripts.extractToken(), null)
                                    }
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView?,
                                    url: String?,
                                    isReload: Boolean,
                                ) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (view == null || url == null) return
                                    val current = currentTarget.value
                                    val nas = viewModel.repo.nas(current.nasId)
                                    val baseUrl = nas?.baseUrl
                                    if (!WebViewConfigurator.isTrustedHost(Uri.parse(url).host, baseUrl)) return
                                    val relayHost = nas?.let { UrlTools.loginHost(it) }
                                    if (UrlTools.isUgreenLoginUrl(url, relayHost)) {
                                        viewModel.diagnostics.log("web: ugos login page detected")
                                        viewModel.diagnostics.detail(
                                            "web: login url=" + viewModel.diagnostics.redactUrl(url)
                                        )
                                        viewModel.onLoginPageDetected(current)
                                    } else {
                                        view.evaluateJavascript(LoginScripts.extractToken(), null)
                                    }
                                    // SPA 路由切换后新渲染的深层页面可能再次出现 vh=0 的布局，
                                    // 这里补一次修复（延迟一次覆盖异步渲染）
                                    view.evaluateJavascript(VH_FIX_SCRIPT) { result ->
                                        val n = result?.trim('"')?.toIntOrNull() ?: 0
                                        if (n > 0) viewModel.diagnostics.detail("web: spa vhFix=$n")
                                    }
                                    view.postDelayed({
                                        if (view !== webView) return@postDelayed
                                        view.evaluateJavascript(VH_FIX_SCRIPT) { result ->
                                            val n = result?.trim('"')?.toIntOrNull() ?: 0
                                            if (n > 0) viewModel.diagnostics.detail("web: spa vhFix=$n (late)")
                                        }
                                    }, SPA_VH_FIX_MS)
                                    view.postDelayed({
                                        if (view !== webView) return@postDelayed
                                        view.evaluateJavascript(SHORT_PAGE_SCRIPT) { result ->
                                            if (result?.trim('"') == "short") {
                                                view.evaluateJavascript(PAGE_INFO_SCRIPT) { info ->
                                                    viewModel.diagnostics.detail(
                                                        "web page(spa): " +
                                                            viewModel.diagnostics.redactUrl(info.orEmpty())
                                                    )
                                                }
                                            }
                                        }
                                    }, SPA_SHORT_CHECK_MS)
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val uri = request?.url ?: return false
                                    val scheme = uri.scheme?.lowercase()
                                    if (scheme == "http" || scheme == "https") {
                                        // 子框架请求不弹窗，也不拉起浏览器
                                        if (request?.isForMainFrame == false) return false
                                        if (uri.toString() == pendingInternal) {
                                            pendingInternal = null
                                            return false
                                        }
                                        val current = currentTarget.value
                                        val baseUrl = viewModel.repo.nas(current.nasId)?.baseUrl
                                        if (WebViewConfigurator.isTrustedHost(uri.host, baseUrl)) return false
                                        when (externalLinkMode) {
                                            "internal" -> {
                                                viewModel.diagnostics.detail("web: link ${uri.host} -> internal")
                                                return false
                                            }
                                            "external" -> {
                                                viewModel.diagnostics.detail("web: link ${uri.host} -> external")
                                                openExternal(context, uri)
                                                return true
                                            }
                                            else -> {
                                                viewModel.diagnostics.detail("web: link ${uri.host} -> ask")
                                                rememberChoice = false
                                                pendingLink = uri
                                                return true
                                            }
                                        }
                                    }
                                    return runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, uri)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                        true
                                    }.getOrDefault(true)
                                }

                                /**
                                 * 动态 chunk（*.async.js）在部分设备上会被 WebView 的连接层挂起，
                                 * 既不报错也不返回。这里改用 OkHttp 拉取并回填，
                                 * 失败则回退给 WebView。
                                 */
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): WebResourceResponse? {
                                    val uri = request?.url ?: return null
                                    if (request.method != "GET") return null
                                    val path = uri.path?.lowercase().orEmpty()
                                    if (!path.endsWith(".async.js")) return null
                                    val current = currentTarget.value
                                    val baseUrl = viewModel.repo.nas(current.nasId)?.baseUrl
                                    if (!WebViewConfigurator.isTrustedHost(uri.host, baseUrl)) return null
                                    return try {
                                        val builder = okhttp3.Request.Builder()
                                            .url(uri.toString())
                                            .header(
                                                "User-Agent",
                                                if (current.shortcut.desktopUa) {
                                                    com.ugport.network.UgreenApi.DESKTOP_UA
                                                } else {
                                                    android.webkit.WebSettings.getDefaultUserAgent(context)
                                                },
                                            )
                                            .header("Accept", "*/*")
                                            .header("Referer", current.url)
                                        CookieManager.getInstance().getCookie(uri.toString())
                                            ?.let { builder.header("Cookie", it) }
                                        val started = System.currentTimeMillis()
                                        viewModel.httpClient.newCall(builder.build()).execute().use { resp ->
                                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                                            val headers = resp.headers.toMultimap()
                                                .filterKeys {
                                                    it.lowercase() !in setOf(
                                                        "content-encoding", "content-length",
                                                        "transfer-encoding", "connection",
                                                        "content-type",
                                                    )
                                                }
                                                .mapValues { it.value.joinToString(", ") }
                                            val cost = System.currentTimeMillis() - started
                                            // 失败进诊断；单纯变慢只进 logcat
                                            val file = path.substringAfterLast('/')
                                            if (resp.code !in 200..299) {
                                                viewModel.diagnostics.log(
                                                    "web: chunk ${resp.code} ${cost}ms $file"
                                                )
                                            } else if (cost > 1500) {
                                                viewModel.diagnostics.detail(
                                                    "web: chunk slow ${cost}ms $file"
                                                )
                                            }
                                            WebResourceResponse(
                                                "application/javascript",
                                                "UTF-8",
                                                resp.code,
                                                if (resp.code in 200..299) "OK" else resp.message.ifBlank { "ERR" },
                                                headers,
                                                java.io.ByteArrayInputStream(bytes),
                                            )
                                        }
                                    } catch (e: Exception) {
                                        viewModel.diagnostics.log(
                                            "web: fetch failed ${path.substringAfterLast('/')}: ${e.message}"
                                        )
                                        null
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        errorText = "页面加载失败：${error?.description ?: "未知错误"}"
                                        viewModel.diagnostics.log("web: error ${error?.description ?: "?"}")
                                        viewModel.diagnostics.detail(
                                            "web: error url=" +
                                                viewModel.diagnostics.redactUrl(
                                                    request.url?.toString().orEmpty()
                                                )
                                        )
                                    } else {
                                        // 子资源加载失败是“框架出来了但页面空白”的常见原因
                                        viewModel.diagnostics.detail(
                                            "web: resource error ${error?.description ?: "?"} " +
                                                viewModel.diagnostics.redactUrl(
                                                    request?.url?.toString().orEmpty()
                                                )
                                        )
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?,
                                ) {
                                    val status = errorResponse?.statusCode ?: 0
                                    if (request?.isForMainFrame == true) {
                                        errorText = "页面加载失败：HTTP $status"
                                        viewModel.diagnostics.log("web: http $status")
                                        viewModel.diagnostics.detail(
                                            "web: http $status url=" +
                                                viewModel.diagnostics.redactUrl(
                                                    request.url?.toString().orEmpty()
                                                )
                                        )
                                    } else if (status >= 400) {
                                        viewModel.diagnostics.detail(
                                            "web: resource $status " +
                                                viewModel.diagnostics.redactUrl(
                                                    request?.url?.toString().orEmpty()
                                                )
                                        )
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?,
                                ): Boolean {
                                    fullscreenHost.hide("renderer gone")
                                    // 重建时销毁旧实例也可能触发这个回调，别影响新一次播放
                                    if (view !== webView) {
                                        viewModel.diagnostics.detail("web: renderer gone (stale)")
                                        return true
                                    }
                                    val crashed = detail?.didCrash() ?: false
                                    val priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        detail?.rendererPriorityAtExit()?.toString() ?: "?"
                                    } else {
                                        "?"
                                    }
                                    viewModel.diagnostics.log(
                                        "web: renderer gone crash=" + crashed + " prio=" + priority
                                    )
                                    if (mediaEnabled) playback.onRendererGone()
                                    rendererDead = true
                                    errorText = null
                                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                        // 不要在回调栈内 destroy，挪到下一轮消息里重建
                                        mainHandler.post { rebuildWebView("renderer gone") }
                                    } else {
                                        // 后台先保留状态与通知，回前台再重建
                                        recovering = true
                                        showRendererHintIfNeeded()
                                    }
                                    return true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowCustomView(
                                    view: View?,
                                    callback: WebChromeClient.CustomViewCallback?,
                                ) {
                                    if (view == null || callback == null) {
                                        runCatching { callback?.onCustomViewHidden() }
                                        return
                                    }
                                    val current = webView
                                    if (current == null) {
                                        runCatching { callback.onCustomViewHidden() }
                                        return
                                    }
                                    fullscreenHost.show(current, view, callback)
                                }

                                override fun onHideCustomView() {
                                    fullscreenHost.hide("web", notifyWebView = false)
                                }

                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onShowFileChooser(
                                    view: WebView?,
                                    callback: ValueCallback<Array<Uri>>?,
                                    params: FileChooserParams?,
                                ): Boolean {
                                    fileCallback?.onReceiveValue(null)
                                    fileCallback = callback
                                    val intent = params?.createIntent() ?: return false
                                    return runCatching {
                                        fileLauncher.launch(intent)
                                        true
                                    }.getOrElse {
                                        fileCallback = null
                                        false
                                    }
                                }

                                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                    if (message != null) {
                                        val level = message.messageLevel()
                                        if (level == ConsoleMessage.MessageLevel.ERROR ||
                                            level == ConsoleMessage.MessageLevel.WARNING
                                        ) {
                                            val body = viewModel.diagnostics.redactUrl(
                                                "${message.message().take(200)} " +
                                                    "@${message.sourceId()}:${message.lineNumber()}"
                                            )
                                            val key = "$level|$body"
                                            val now = System.currentTimeMillis()
                                            // 第三方页面常连续输出同一条，5 秒内只记一次
                                            if (key != lastConsoleKey || now - lastConsoleAt >= CONSOLE_DEDUP_MS) {
                                                lastConsoleKey = key
                                                lastConsoleAt = now
                                                if (level == ConsoleMessage.MessageLevel.ERROR) {
                                                    viewModel.diagnostics.log("web console [ERROR] $body")
                                                } else {
                                                    viewModel.diagnostics.detail("web console [WARN] $body")
                                                }
                                            }
                                        }
                                    }
                                    return true
                                }

                                override fun onPermissionRequest(request: PermissionRequest?) {
                                    if (request == null) return
                                    val host = webView?.url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                                    val current = currentTarget.value
                                    val baseUrl = viewModel.repo.nas(current.nasId)?.baseUrl
                                    if (WebViewConfigurator.isTrustedHost(host, baseUrl)) {
                                        request.grant(request.resources)
                                    } else {
                                        request.deny()
                                    }
                                }
                            }
                            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                                enqueueDownload(
                                    context,
                                    url,
                                    contentDisposition,
                                    mimeType,
                                    this@apply.settings.userAgentString,
                                )
                            }
                            // 不要在 factory 里预加载 target.url：
                            // 那是 dockerToken 的一次性鉴权页，
                            // WebView 抢先加载会消费掉 token 并跳到 /，
                            // 导致设置了页面路径的端口首次打开落到根路径（可能 404）。
                            webView = this
                        }
                    },
                    // WebView 会被复用：切换端口时在此同步 UA / 视口模式
                    update = { WebViewConfigurator.applyUaMode(it, desktopUa) },
                )
        }

        WebProgress(
            progress = progress,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )

                androidx.compose.animation.AnimatedVisibility(
                    visible = recovering && errorText == null,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 3 },
                    exit = fadeOut(tween(160)),
                ) {
                    WebGpuHint(
                        text = "网页进程被系统回收，正在恢复…",
                        onDismiss = { recovering = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 12.dp, end = 12.dp, top = 64.dp, bottom = 10.dp),
                    )
                }

                val gpuHint = if (gpuDismissed) null else webGpuHintText(webGpuVerdict(gpuProbe))
                // 外层是 Box，显式调用顶层 AnimatedVisibility，避免解析到其他作用域扩展
                androidx.compose.animation.AnimatedVisibility(
                    visible = gpuHint != null && errorText == null && !recovering,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 3 },
                    exit = fadeOut(tween(160)),
                ) {
                    WebGpuHint(
                        text = gpuHint.orEmpty(),
                        onDismiss = { gpuDismissed = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 12.dp, end = 12.dp, top = 64.dp, bottom = 10.dp),
                    )
                }

                val currentError = errorText
                if (currentError != null) {
                    WebErrorCard(
                        message = currentError,
                        onRetry = {
                            errorText = null
                            rebuildWebView("retry")
                        },
                    )
                }

        androidx.compose.animation.AnimatedVisibility(
            visible = controlsExpanded,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp, end = 12.dp),
            enter = fadeIn(tween(160)) + slideInHorizontally(tween(200)) { it / 3 },
            exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { it / 3 },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WebFab(
                    icon = Icons.Filled.Refresh,
                    contentDescription = "刷新",
                    onClick = {
                        if (rendererDead) rebuildWebView("refresh") else webView?.reload()
                        controlsReset++
                    },
                )
                WebFab(
                    icon = Icons.Filled.Home,
                    contentDescription = "退出端口页",
                    onClick = onBack,
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !controlsExpanded,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 8.dp),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)),
        ) {
            CollapsedControlsBar {
                controlsExpanded = true
                controlsReset++
            }
        }
    }

    val linkToOpen = pendingLink
    if (linkToOpen != null) {
        GlassActionSheet(
            onDismissRequest = {
                pendingLink = null
                rememberChoice = false
            },
            title = linkToOpen.host ?: "打开链接",
            message = linkToOpen.toString(),
            footer = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "记住我的选择",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it },
                    )
                }
            },
            actions = listOf(
                SheetAction(
                    label = "在应用内打开",
                    onClick = {
                        if (rememberChoice) onExternalLinkModeChange("internal")
                        pendingInternal = linkToOpen.toString()
                        pendingLink = null
                        rememberChoice = false
                        webView?.loadUrl(linkToOpen.toString())
                    },
                ),
                SheetAction(
                    label = "用浏览器打开",
                    onClick = {
                        if (rememberChoice) onExternalLinkModeChange("external")
                        pendingLink = null
                        rememberChoice = false
                        openExternal(context, linkToOpen)
                    },
                ),
            ),
        )
    }

    if (showBatteryDialog) {
        ConfirmDialog(
            title = "后台播放保护",
            message = "为了避免锁屏或切到后台后被系统中断，建议允许绿联快连忽略电池优化。\n\n" +
                "小米/红米还需要：设置 → 应用设置 → 绿联快连 → 省电策略 → 无限制，并允许自启动；" +
                "最近任务界面下拉应用卡片可锁定后台。",
            confirmText = "去设置",
            dismissText = "以后再说",
            onConfirm = {
                showBatteryDialog = false
                BatteryOptimization.openRequest(context)
            },
            onDismiss = { showBatteryDialog = false },
        )
    }

    if (showRendererHint) {
        ConfirmDialog(
            title = "后台播放被系统打断",
            message = "系统回收了网页进程，播放会中断。\n\n" +
                "建议在最近任务界面下拉绿联快连的卡片锁定后台，" +
                "并确认省电策略为「无限制」、允许自启动。",
            confirmText = "去应用详情",
            dismissText = "知道了",
            onConfirm = {
                showRendererHint = false
                markRendererHintShown()
                BatteryOptimization.openAppDetails(context)
            },
            onDismiss = {
                showRendererHint = false
                markRendererHintShown()
            },
        )
    }

    LaunchedEffect(target.url, webGeneration) {
        fullscreenHost.hide("navigate")
        controlsExpanded = true
        controlsReset++
        gpuPageToken++
        gpuProbedToken = -1
        gpuProbe = null
        gpuDismissed = false
        gpuLogSignature = ""
        if (mediaEnabled) {
            playback.onPageStarted()
        } else {
            // 关闭后台播放的端口不继承上一个端口的前台服务/通知
            playback.detach()
        }
        blankRetriedUrl = null
        // 等待 AndroidView 创建 WebView。
        // 只用 target.url 作为 key，避免 WebView 状态变化导致重复准备鉴权
        val view = snapshotFlow { webView }.filterNotNull().first()
        WebViewConfigurator.applyUaMode(view, desktopUa)
        val loadUrl = viewModel.prepareDockerRedirect(target)
        // 重建时优先回到旧页面地址（保住 SPA 路由/章节），否则用鉴权后的入口路径
        val restore = pendingRestoreUrl
        pendingRestoreUrl = null
        seedCookies(cookieJar, target.url)
        errorText = null
        progress = PROGRESS_STARTED
        val webViewPackage = WebViewCompat.getCurrentWebViewPackage(view.context)
        viewModel.diagnostics.detail(
            "web: webview=${webViewPackage?.packageName} ${webViewPackage?.versionName}"
        )
        // 无论 WebView 之前是否已经加载过目标地址，拿到 Cookie 后都重新加载一次，
        // 避免第一次无 Cookie 的请求把页面渲染成空白/未登录状态。
        // 用当前生效的 UA（移动端为系统默认 UA，桌面端为 DESKTOP_UA）
        view.loadUrl(restore ?: loadUrl, mapOf("User-Agent" to view.settings.userAgentString))
    }

    // 允许后台播放的端口常驻高优先级，不随 hasMedia 降级（切章瞬间最容易掉优先级）
    LaunchedEffect(webView) {
        if (!mediaEnabled) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        runCatching {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
    }

    DisposableEffect(Unit) {
        if (mediaEnabled) {
            playback.onPlaybackStarted = { requestNotificationPermissionIfNeeded() }
            playback.onRebuildRequested = { reason ->
                mainHandler.post { rebuildWebView(reason) }
            }
        }
        onDispose {
            disposed = true
            playback.onPlaybackStarted = null
            playback.onRebuildRequested = null
            mainHandler.removeCallbacksAndMessages(null)
            fullscreenHost.hide("dispose")
            gpuPageToken++
            // 先断开媒体控制并停止前台服务，再销毁 WebView
            playback.detach(mediaControllerHolder[0])
            mediaControllerHolder[0] = null
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            webView?.let { view ->
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                runCatching { view.destroy() }
            }
            webView = null
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                fullscreenHost.hide("activity destroy")
                return@LifecycleEventObserver
            }
            if (!mediaEnabled) {
                // 不允许后台播放：离开前台先暂停页面音频，再暂停 WebView 渲染与定时器
                when (event) {
                    Lifecycle.Event.ON_START -> webView?.onResume()
                    Lifecycle.Event.ON_STOP -> {
                        webView?.evaluateJavascript(MEDIA_PAUSE_SCRIPT, null)
                        webView?.onPause()
                    }
                    else -> Unit
                }
            } else if (event == Lifecycle.Event.ON_START) {
                val view = webView
                if (view != null && !rendererDead) {
                    if (mediaEnabled) {
                        runCatching {
                            view.setRendererPriorityPolicy(
                                WebView.RENDERER_PRIORITY_IMPORTANT,
                                false,
                            )
                        }
                    }
                    // 只在「状态说在播放、却很久没有上报」时回声确认，
                    // 避免正常暂停/阅读时误判重建
                    if (playback.isPlaying &&
                        !playback.hasRecentReport(RENDERER_REPORT_WINDOW_MS)
                    ) {
                        probeRendererAlive(view)
                    }
                    // inject 是幂等的，页面在后台重载/渲染进程恢复后补一次注入
                    runCatching { view.evaluateJavascript(MediaHookScript.inject(), null) }
                    runCatching { view.evaluateJavascript(MediaHookScript.probe(), null) }
                } else if (rendererDead) {
                    mainHandler.post { rebuildWebView("onstart") }
                }
                playback.onHostResumed()
                if (playback.isPlaying) requestNotificationPermissionIfNeeded()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** 关闭「允许后台播放」的端口在离开前台时调用，不依赖已注入的媒体脚本。 */
