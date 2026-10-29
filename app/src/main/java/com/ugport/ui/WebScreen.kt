package com.ugport.ui

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import com.ugport.media.PlaybackHub
import com.ugport.media.WebMediaController
import com.ugport.network.HttpFactory
import com.ugport.ui.components.ConfirmDialog
import com.ugport.ui.components.GlassActionSheet
import com.ugport.ui.components.GlassLevel
import com.ugport.ui.components.GlassSurface
import com.ugport.ui.components.SheetAction
import com.ugport.ui.components.glass
import com.ugport.ui.components.WebGpuHint
import com.ugport.util.BatteryOptimization
import com.ugport.util.UrlTools
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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
    // 页面骨架加载完成但一直没有文字时，自动重载一次（并发 chunk 请求可能被代理挂起）
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
            if (playback.isPlaying) return@postDelayed
            if (view.url == url && blankRetriedUrl != url) {
                view.evaluateJavascript(BLANK_CHECK_SCRIPT) { result ->
                    val blank = result?.trim('"') == "blank"
                    if (blank) {
                        viewModel.diagnostics.log(
                            "web: blank check=true " + viewModel.diagnostics.redactUrl(url)
                        )
                        // 给前端留出初始化时间，二次确认后再动缓存
                        view.postDelayed({
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
        }, 6000L)
    }

    /**
     * 页面初始化较慢时（如 qBittorrent 要先跑完一遍 JS 才建出面板），
     * 1.2s 后再查一次高度；仍然不足就再补诊断并重试一次修复。
     */
    fun scheduleHeightFollowUp(view: WebView, url: String, attempt: Int = 0) {
        if (attempt >= 2) return
        view.postDelayed({
            if (view.url != url) return@postDelayed
            view.evaluateJavascript(SHORT_PAGE_SCRIPT) { result ->
                if (result?.trim('"') == "short") {
                    view.evaluateJavascript(PAGE_INFO_SCRIPT) { info ->
                        viewModel.diagnostics.detail("web page+: " + viewModel.diagnostics.redactUrl(info.orEmpty()))
                    }
                    scheduleHeightFollowUp(view, url, attempt + 1)
                }
            }
        }, if (attempt == 0) 1200L else 2000L)
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
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            WebViewConfigurator.configure(this, desktopUa, mediaEnabled)
                            addJavascriptInterface(
                                WebBridge { token ->
                                    val current = currentTarget.value
                                    viewModel.onWebToken(current.nasId, token, current.shortcut)
                                },
                                "UgBridge",
                            )
                            if (mediaEnabled) {
                                val mediaController = object : WebMediaController {
                                    override fun evaluate(script: String) {
                                        post { runCatching { evaluateJavascript(script, null) } }
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
                                    fullscreenHost.hide("page start")
                                    gpuPageToken++
                                    gpuProbe = null
                                    if (mediaEnabled) playback.onPageStarted()
                                    progress = 10
                                    if (url != null) {
                                        if (url == pendingInternal) pendingInternal = null
                                        viewModel.diagnostics.log("web: start ${viewModel.diagnostics.redactUrl(url)}")
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    progress = 100
                                    if (view == null || url == null) return
                                    if (view.url == url) {
                                        view.evaluateJavascript(PAGE_INFO_SCRIPT) { result ->
                                            viewModel.diagnostics.detail(
                                                "web page: ${viewModel.diagnostics.redactUrl(result.orEmpty())}"
                                            )
                                        }
                                        if (mediaEnabled) {
                                            playback.onPageLoaded()
                                            view.post {
                                                view.evaluateJavascript("window.__ugMediaVersion === 4") { result ->
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
                                        viewModel.diagnostics.log(
                                            "web: ugos login page detected ${viewModel.diagnostics.redactUrl(url)}"
                                        )
                                        viewModel.onLoginPageDetected(currentTarget.value)
                                        if (lastAutoFill != url && autoFillCount < 3) {
                                            lastAutoFill = url
                                            autoFillCount++
                                            // 原生重授权通常 1-2 秒完成；4 秒后仍停在登录页再自动填充
                                            view.postDelayed({
                                                if (!viewModel.busy && viewModel.otpPrompt == null &&
                                                    UrlTools.isUgreenLoginUrl(view.url, relayHost)
                                                ) {
                                                    fillCredentials()
                                                }
                                            }, 4000L)
                                            view.postDelayed({
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
                                        viewModel.diagnostics.log(
                                            "web: ugos login page detected ${viewModel.diagnostics.redactUrl(url)}"
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
                                        view.evaluateJavascript(VH_FIX_SCRIPT) { result ->
                                            val n = result?.trim('"')?.toIntOrNull() ?: 0
                                            if (n > 0) viewModel.diagnostics.detail("web: spa vhFix=$n (late)")
                                        }
                                    }, 600L)
                                    view.postDelayed({
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
                                    }, 800L)
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
                                 * 既不报错也不返回。这里改用 OkHttp 拉取并回填，失败则回退给 WebView。
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
                                        viewModel.diagnostics.log(
                                            "web: error ${error?.description ?: "?"} ${viewModel.diagnostics.redactUrl(request.url?.toString().orEmpty())}"
                                        )
                                    } else {
                                        // 子资源加载失败是“框架出来了但页面空白”的常见原因
                                        viewModel.diagnostics.log(
                                            "web: resource error ${error?.description ?: "?"} " +
                                                viewModel.diagnostics.redactUrl(request?.url?.toString().orEmpty())
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
                                        viewModel.diagnostics.log(
                                            "web: http $status ${viewModel.diagnostics.redactUrl(request.url?.toString().orEmpty())}"
                                        )
                                    } else if (status >= 400) {
                                        viewModel.diagnostics.log(
                                            "web: resource $status ${viewModel.diagnostics.redactUrl(request?.url?.toString().orEmpty())}"
                                        )
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?,
                                ): Boolean {
                                    fullscreenHost.hide("renderer gone")
                                    errorText = "WebView 渲染进程已退出，请返回重试"
                                    viewModel.diagnostics.log("web: render process gone")
                                    if (mediaEnabled) playback.onRendererGone()
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
                                            if (key != lastConsoleKey || now - lastConsoleAt >= 5_000) {
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
                            // 那是 dockerToken 的一次性鉴权页，WebView 抢先加载会消费掉 token 并跳到 /，
                            // 导致设置了页面路径的端口首次打开落到根路径（可能 404）。
                            webView = this
                        }
                    },
                    // WebView 会被复用：切换端口时在此同步 UA / 视口模式
                    update = { WebViewConfigurator.applyUaMode(it, desktopUa) },
                )

        WebProgress(
            progress = progress,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )

                val gpuHint = if (gpuDismissed) null else webGpuHintText(webGpuVerdict(gpuProbe))
                // 外层是 Box，显式调用顶层 AnimatedVisibility，避免解析到其他作用域扩展
                androidx.compose.animation.AnimatedVisibility(
                    visible = gpuHint != null && errorText == null,
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        GlassSurface(
                            modifier = Modifier.widthIn(max = 340.dp),
                            level = GlassLevel.Strong,
                            padding = PaddingValues(24.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(
                                            MaterialTheme.colorScheme.errorContainer,
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "页面加载失败",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = currentError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        errorText = null
                                        webView?.reload()
                                    },
                                ) { Text("重试") }
                            }
                        }
                    }
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
                        webView?.reload()
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
                "小米/红米还需要：设置 → 应用设置 → 绿联快连 → 省电策略 → 无限制，并允许自启动。",
            confirmText = "去设置",
            dismissText = "以后再说",
            onConfirm = {
                showBatteryDialog = false
                BatteryOptimization.openRequest(context)
            },
            onDismiss = { showBatteryDialog = false },
        )
    }

    LaunchedEffect(target.url) {
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
        // 等待 AndroidView 创建 WebView；只用 target.url 作为 key，避免 WebView 状态变化导致重复准备鉴权
        val view = snapshotFlow { webView }.filterNotNull().first()
        WebViewConfigurator.applyUaMode(view, desktopUa)
        val loadUrl = viewModel.prepareDockerRedirect(target)
        seedCookies(cookieJar, target.url)
        errorText = null
        progress = 10
        val webViewPackage = WebViewCompat.getCurrentWebViewPackage(view.context)
        viewModel.diagnostics.detail(
            "web: webview=${webViewPackage?.packageName} ${webViewPackage?.versionName}"
        )
        // 无论 WebView 之前是否已经加载过目标地址，拿到 Cookie 后都重新加载一次，
        // 避免第一次无 Cookie 的请求把页面渲染成空白/未登录状态。
        // 用当前生效的 UA（移动端为系统默认 UA，桌面端为 DESKTOP_UA）
        view.loadUrl(loadUrl, mapOf("User-Agent" to view.settings.userAgentString))
    }

    LaunchedEffect(webView) {
        if (!mediaEnabled) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        playback.state
            .map { it.hasMedia }
            .distinctUntilChanged()
            .collect { hasMedia ->
                runCatching {
                    view.setRendererPriorityPolicy(
                        if (hasMedia) {
                            WebView.RENDERER_PRIORITY_IMPORTANT
                        } else {
                            WebView.RENDERER_PRIORITY_BOUND
                        },
                        false,
                    )
                }
            }
    }

    DisposableEffect(Unit) {
        if (mediaEnabled) {
            playback.onPlaybackStarted = { requestNotificationPermissionIfNeeded() }
        }
        onDispose {
            playback.onPlaybackStarted = null
            fullscreenHost.hide("dispose")
            gpuPageToken++
            // 先断开媒体控制并停止前台服务，再销毁 WebView
            playback.detach(mediaControllerHolder[0])
            mediaControllerHolder[0] = null
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            webView?.destroy()
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
                // inject 是幂等的，页面在后台重载/渲染进程恢复后补一次注入
                webView?.evaluateJavascript(MediaHookScript.inject(), null)
                webView?.evaluateJavascript(MediaHookScript.probe(), null)
                playback.onHostResumed()
                if (playback.isPlaying) requestNotificationPermissionIfNeeded()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/** 关闭「允许后台播放」的端口在离开前台时调用，不依赖已注入的媒体脚本。 */
private const val CONTROLS_AUTO_HIDE_MS = 5_000L

private val GPU_PROBE_READ_DELAYS = longArrayOf(250L, 600L, 1200L, 1800L)

private const val MEDIA_PAUSE_SCRIPT =
    "(function(){try{var m=document.querySelectorAll('audio,video');" +
        "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}}catch(e){}})();"

private val VH_FIX_SCRIPT = """
    (function(){
      try {
        var h = window.innerHeight;
        if (!document.body || !h) return 0;
        // 只有 vh 真的算成 0 时才处理
        var probe = document.createElement('div');
        probe.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;';
        document.documentElement.appendChild(probe);
        probe.style.height = '100vh';
        var unit = probe.getBoundingClientRect().height;
        document.documentElement.removeChild(probe);
        if (unit > 0) return 0;
        function convert(v){
          return v.replace(/([0-9.]+)(dvh|svh|lvh|vh)/g, function(m, n){
            return (parseFloat(n) * h / 100) + 'px';
          });
        }
        var count = 0;
        function walk(rules){
          for (var i = 0; i < rules.length; i++) {
            var r = rules[i];
            if (r.style) {
              var props = ['height', 'minHeight', 'maxHeight'];
              for (var p = 0; p < props.length; p++) {
                var v = r.style[props[p]];
                if (v && /(dvh|svh|lvh|vh)/.test(v)) {
                  try { r.style[props[p]] = convert(v); count++; } catch (e) {}
                }
              }
            }
            if (r.cssRules && r.cssRules.length) walk(r.cssRules);
          }
        }
        for (var i = 0; i < document.styleSheets.length; i++) {
          try { var rules = document.styleSheets[i].cssRules; if (rules) walk(rules); } catch (e) {}
        }
        var inline = document.querySelectorAll('[style*="vh"]');
        for (var j = 0; j < inline.length; j++) {
          var props2 = ['height', 'minHeight', 'maxHeight'];
          for (var k = 0; k < props2.length; k++) {
            var iv = inline[j].style[props2[k]];
            if (iv && /(dvh|svh|lvh|vh)/.test(iv)) {
              try { inline[j].style[props2[k]] = convert(iv); count++; } catch (e) {}
            }
          }
        }
        if (count > 0) { try { window.dispatchEvent(new Event('resize')); } catch (e) {} }
        return count;
      } catch (e) { return 0; }
    })();
""".trimIndent()

private val STALL_SCRIPT = """
    (function(){
      try {
        var b = document.body;
        if (!b) return 'ok';
        return (b.innerText || '').trim().length === 0 ? 'stall' : 'ok';
      } catch (e) { return 'ok'; }
    })();
""".trimIndent()

private val SHORT_PAGE_SCRIPT = """
    (function(){
      try {
        var vh = window.innerHeight, b = document.body;
        if (!b || vh <= 0) return 'skip';
        return b.getBoundingClientRect().height < vh * 0.5 ? 'short' : 'ok';
      } catch (e) { return 'ok'; }
    })();
""".trimIndent()

private const val BLANK_CHECK_DELAY_MS = 900L
private const val BLANK_RECHECK_MS = 900L

/**
 * 空白页判定：body 高度为 0，或既没有文字也没有可见的图片/画布/控件。
 * 只用于“清缓存重载一次”的兜底，不做像素截图判断。
 */
private val BLANK_CHECK_SCRIPT = """
    (function(){
      try {
        var b = document.body;
        if (!b) return 'blank';
        // 有文字就说明页面并非空白（body 高度 0 属于布局问题，由 PAGE_INFO_SCRIPT 修复）
        if ((b.innerText || '').trim().length > 0) return 'ok';
        var nodes = document.querySelectorAll('img,canvas,video,svg,iframe,input,button,select,textarea');
        for (var i = 0; i < nodes.length; i++) {
          var style = getComputedStyle(nodes[i]);
          var rect = nodes[i].getBoundingClientRect();
          if (style.display !== 'none' && style.visibility !== 'hidden' &&
              rect.width > 2 && rect.height > 2) return 'ok';
        }
        return 'blank';
      } catch (e) { return 'ok'; }
    })();
""".trimIndent()

private val PAGE_INFO_SCRIPT = """
    (function(){
      try {
        var vw = window.innerWidth, vh = window.innerHeight;
        var repaired = [];
        function rectOf(el){
          if (!el) return null;
          var b = el.getBoundingClientRect();
          return [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)];
        }
        // 部分 WebUI（qBittorrent、PanSou）在 WebView 里 body 高度会算成 0：
        // 可能是 body 塌陷，也可能是 100dvh 解析为 0 后被 overflow:hidden 裁剪。
        function repairBodyHeight(){
          if (!document.body || vh <= 0) return 'skip';
          var bh = document.body.getBoundingClientRect().height;
          // 高度达到视口一半以上视为正常；否则可能是视口单位失效，
          // 或 WebUI 在 WebView 完成布局前按旧的 innerHeight 算好了尺寸（如 qBittorrent）。
          if (bh >= vh * 0.5) return 'ok';
          // 该 WebView 里 dvh/vh 视口单位可能全部算成 0，百分比高度也随之失效，
          // 所以直接用 window.innerHeight 换算成像素写内联样式。
          if (document.documentElement) {
            document.documentElement.style.setProperty('height', vh + 'px', 'important');
          }
          document.body.style.setProperty('height', vh + 'px', 'important');
          document.body.style.setProperty('min-height', vh + 'px', 'important');
          document.body.style.setProperty('overflow', 'visible', 'important');
          // 仅当 body 完全塌陷时（如 PanSou 的 #app/#app-shell 都为 0），
          // 才需要兜底撑开布局容器；body 只是偏矮时（qBittorrent），
          // 撑 body 即可由 CSS 百分比自然传导，直接改子元素会把页头也拉满、
          // 把 pageWrapper 挤到屏幕外。
          if (bh === 0) {
            var nodes = document.querySelectorAll('body > *, body > * > *');
            for (var i = 0; i < nodes.length; i++) {
              var el = nodes[i], b = el.getBoundingClientRect();
              if (b.width >= vw * 0.9 && b.height === 0) {
                el.style.setProperty('height', vh + 'px', 'important');
                el.style.setProperty('min-height', vh + 'px', 'important');
                repaired.push((el.id || el.className || el.tagName).toString().slice(0, 40));
              }
            }
          }
          // 页面自己的 resize 处理可能抛错（qBittorrent/MooTools），不能影响诊断返回
          try { window.dispatchEvent(new Event('resize')); } catch (e) {}
          return document.body.getBoundingClientRect().height > 0 ? 'fixed' : 'fail';
        }
        // 该 WebView 里 vh/dvh 可能全部算成 0（实测 Android 16 / WebView 143）。
        // 受影响页面会把 height:100vh 的布局容器压成 0，只剩文字不出界面。
        function measureUnit(prop){
          var probe = document.createElement('div');
          probe.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;';
          document.documentElement.appendChild(probe);
          probe.style.height = prop;
          var value = probe.getBoundingClientRect().height;
          document.documentElement.removeChild(probe);
          return value;
        }
        function fixVhUnits(height){
          var count = 0;
          function convert(value){
            return value.replace(/([0-9.]+)(dvh|svh|lvh|vh)/g, function(m, num){
              return (parseFloat(num) * height / 100) + 'px';
            });
          }
          function walk(rules){
            for (var i = 0; i < rules.length; i++) {
              var r = rules[i];
              if (r.style) {
                var props = ['height', 'minHeight', 'maxHeight'];
                for (var p = 0; p < props.length; p++) {
                  var v = r.style[props[p]];
                  if (v && /(dvh|svh|lvh|vh)/.test(v)) {
                    try { r.style[props[p]] = convert(v); count++; } catch (e) {}
                  }
                }
              }
              // Chrome 支持 CSS 嵌套，CSSStyleRule 也有 cssRules，不能据此跳过 style
              if (r.cssRules && r.cssRules.length) walk(r.cssRules);
            }
          }
          for (var i = 0; i < document.styleSheets.length; i++) {
            try { var rules = document.styleSheets[i].cssRules; if (rules) walk(rules); } catch (e) {}
          }
          // 内联 style 里的 vh 也一并换算
          var inline = document.querySelectorAll('[style*="vh"]');
          for (var j = 0; j < inline.length; j++) {
            var props2 = ['height', 'minHeight', 'maxHeight'];
            for (var k = 0; k < props2.length; k++) {
              var iv = inline[j].style[props2[k]];
              if (iv && /(dvh|svh|lvh|vh)/.test(iv)) {
                try { inline[j].style[props2[k]] = convert(iv); count++; } catch (e) {}
              }
            }
          }
          return count;
        }
        var vhUnit = measureUnit('100vh');
        var vhFix = 0;
        if (vhUnit === 0) vhFix = fixVhUnits(vh);
        var bodyBroken = !!(document.body && vh > 0 && document.body.getBoundingClientRect().height < vh * 0.5);
        var bodyFix = repairBodyHeight();
        var e = document.elementFromPoint(vw / 2, vh / 2);
        return JSON.stringify({
          title: document.title || '',
          body: document.body ? document.body.innerText.length : 0,
          ready: document.readyState,
          scripts: document.scripts.length,
          width: vw,
          height: vh,
          bodyFix: bodyFix,
          repaired: repaired,
          vhFix: vhFix,
          vh: vhUnit,
          bodyRect: rectOf(document.body),
          desktop: (function(){ var d=document.getElementById('desktop'); if(!d) return null; var s=getComputedStyle(d),b=d.getBoundingClientRect(); return [s.display,s.visibility,s.opacity,b.x,b.y,b.width,b.height]; })(),
          atCenter: e ? (e.id || (typeof e.className === 'string' ? e.className : '') || e.tagName) : '',
          errors: (window.__ugErrors || []).slice(0, 5)
        });
      } catch (err) {
        return 'err:' + err;
      }
    })();
""".trimIndent()

private fun seedCookies(cookieJar: HttpFactory.MemoryCookieJar, url: String) {
    val manager = CookieManager.getInstance()
    cookieJar.all().forEach { cookie ->
        val host = cookie.domain.removePrefix(".")
        val value = "${cookie.name}=${cookie.value}; path=${cookie.path}; domain=${cookie.domain}"
        runCatching { manager.setCookie("https://$host", value) }
    }
    manager.flush()
}

private fun enqueueDownload(
    context: Context,
    url: String,
    contentDisposition: String?,
    mimeType: String?,
    userAgent: String,
) {
    runCatching {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimeType),
            )
        }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}


@Composable
private fun CollapsedControlsBar(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
        val scrim = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f)
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(36.dp)
                .background(scrim, shape)
                .glass(shape, GlassLevel.Strong),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun WebFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scrim = MaterialTheme.colorScheme.surface.copy(alpha = 0.60f)
    GlassSurface(
        modifier = Modifier
            .size(48.dp)
            .background(scrim, CircleShape),
        shape = CircleShape,
        level = GlassLevel.Strong,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun WebProgress(progress: Int, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(
        targetValue = if (progress > 0) progress.coerceIn(0, 100) / 100f else 0f,
        animationSpec = tween(240, easing = LinearEasing),
        label = "webProgress",
    )
    val visible = progress in 1..99
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "webProgressAlpha",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha)),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        )
    }
}

private fun openExternal(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** LocalActivity 为空时（测试或特殊宿主）沿 ContextWrapper 链找 Activity。 */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
