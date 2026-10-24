package com.ugport.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import com.ugport.network.HttpFactory
import com.ugport.util.UrlTools
import com.ugport.web.LoginScripts
import com.ugport.web.WebBridge
import com.ugport.web.WebViewConfigurator
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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val currentTarget = rememberUpdatedState(target)
    val desktopUa = target.shortcut.desktopUa
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var lastAutoFill by remember { mutableStateOf<String?>(null) }
    var lastExtract by remember { mutableStateOf<String?>(null) }
    var autoFillCount by remember { mutableIntStateOf(0) }
    // 同一 URL 只做一次「清缓存重载」，避免空白页无限循环
    var blankRetriedUrl by remember { mutableStateOf<String?>(null) }
    // 外部链接：待用户选择的目标，以及选择「应用内」后放行一次的 URL
    var pendingLink by remember { mutableStateOf<Uri?>(null) }
    var pendingInternal by remember { mutableStateOf<String?>(null) }
    var rememberChoice by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileCallback
        fileCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
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
            if (view.url == url && blankRetriedUrl != url) {
                view.evaluateJavascript(BLANK_CHECK_SCRIPT) { result ->
                    val blank = result?.trim('"') == "blank"
                    viewModel.diagnostics.log(
                        "web: blank check=$blank " + viewModel.diagnostics.redactUrl(url)
                    )
                    if (blank) {
                        // 给前端留出初始化时间，二次确认后再动缓存
                        view.postDelayed({
                            if (view.url == url && blankRetriedUrl != url) {
                                view.evaluateJavascript(BLANK_CHECK_SCRIPT) { second ->
                                    val stillBlank = second?.trim('"') == "blank"
                                    viewModel.diagnostics.log("web: blank recheck=$stillBlank")
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
                        viewModel.diagnostics.log("web page+: " + viewModel.diagnostics.redactUrl(info.orEmpty()))
                    }
                    scheduleHeightFollowUp(view, url, attempt + 1)
                }
            }
        }, if (attempt == 0) 1200L else 2000L)
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { fillCredentials() }) {
                        Icon(Icons.Filled.Person, contentDescription = "填充账号密码")
                    }
                    IconButton(onClick = { openExternal(context, Uri.parse(target.url)) }) {
                        Icon(Icons.Filled.Share, contentDescription = "在浏览器打开")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            WebViewConfigurator.configure(this, desktopUa)
                            addJavascriptInterface(
                                WebBridge { token ->
                                    val current = currentTarget.value
                                    viewModel.onWebToken(current.nasId, token, current.shortcut)
                                },
                                "UgBridge",
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    super.onPageStarted(view, url, favicon)
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
                                        viewModel.diagnostics.log("web: finish ${viewModel.diagnostics.redactUrl(url)}")
                                        view.evaluateJavascript(PAGE_INFO_SCRIPT) { result ->
                                            viewModel.diagnostics.log("web page: ${viewModel.diagnostics.redactUrl(result.orEmpty())}")
                                        }
                                        scheduleBlankCheck(view, url)
                                        scheduleHeightFollowUp(view, url)
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
                                                viewModel.diagnostics.log("web: link ${uri.host} -> internal")
                                                return false
                                            }
                                            "external" -> {
                                                viewModel.diagnostics.log("web: link ${uri.host} -> external")
                                                openExternal(context, uri)
                                                return true
                                            }
                                            else -> {
                                                viewModel.diagnostics.log("web: link ${uri.host} -> ask")
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
                                    errorText = "WebView 渲染进程已退出，请返回重试"
                                    viewModel.diagnostics.log("web: render process gone")
                                    return true
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
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
                                            viewModel.diagnostics.log(
                                                "web console [$level] ${message.message()} " +
                                                    "@${message.sourceId()}:${message.lineNumber()}"
                                            )
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

                val currentError = errorText
                if (currentError != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Card {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(currentError, style = MaterialTheme.typography.bodyMedium)
                                Button(
                                    onClick = {
                                        errorText = null
                                        webView?.reload()
                                    },
                                    modifier = Modifier.padding(top = 12.dp),
                                ) { Text("重试") }
                            }
                        }
                    }
                }
            }
        }
    }

    val linkToOpen = pendingLink
    if (linkToOpen != null) {
        AlertDialog(
            onDismissRequest = {
                pendingLink = null
                rememberChoice = false
            },
            title = { Text("打开链接") },
            text = {
                Column {
                    Text(
                        linkToOpen.host ?: linkToOpen.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        linkToOpen.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = rememberChoice,
                            onCheckedChange = { rememberChoice = it },
                        )
                        Text("记住我的选择", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (rememberChoice) onExternalLinkModeChange("internal")
                    pendingInternal = linkToOpen.toString()
                    pendingLink = null
                    rememberChoice = false
                    webView?.loadUrl(linkToOpen.toString())
                }) { Text("应用内打开") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        if (rememberChoice) onExternalLinkModeChange("external")
                        pendingLink = null
                        rememberChoice = false
                        openExternal(context, linkToOpen)
                    }) { Text("浏览器打开") }
                    TextButton(onClick = {
                        pendingLink = null
                        rememberChoice = false
                    }) { Text("取消") }
                }
            },
        )
    }

    LaunchedEffect(target.url) {
        blankRetriedUrl = null
        // 等待 AndroidView 创建 WebView；只用 target.url 作为 key，避免 WebView 状态变化导致重复准备鉴权
        val view = snapshotFlow { webView }.filterNotNull().first()
        WebViewConfigurator.applyUaMode(view, desktopUa)
        val loadUrl = viewModel.prepareDockerRedirect(target)
        seedCookies(cookieJar, target.url)
        errorText = null
        progress = 10
        val webViewPackage = WebViewCompat.getCurrentWebViewPackage(view.context)
        viewModel.diagnostics.log(
            "web: webview=${webViewPackage?.packageName} ${webViewPackage?.versionName}"
        )
        viewModel.diagnostics.log("web: load ${viewModel.diagnostics.redactUrl(loadUrl)}")
        // 无论 WebView 之前是否已经加载过目标地址，拿到 Cookie 后都重新加载一次，
        // 避免第一次无 Cookie 的请求把页面渲染成空白/未登录状态。
        // 用当前生效的 UA（移动端为系统默认 UA，桌面端为 DESKTOP_UA）
        view.loadUrl(loadUrl, mapOf("User-Agent" to view.settings.userAgentString))
    }

    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            webView?.destroy()
            webView = null
        }
    }
}

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
        var dvh = measureUnit('100dvh');
        var vhFix = 0;
        if (vhUnit === 0) vhFix = fixVhUnits(vh);
        var bodyBroken = !!(document.body && vh > 0 && document.body.getBoundingClientRect().height < vh * 0.5);
        var bodyFix = repairBodyHeight();
        var e = document.elementFromPoint(vw / 2, vh / 2);
        var first = document.body && document.body.firstElementChild;
        // 前 3 层、最多 12 个节点的尺寸，便于定位“只显示一部分”的页面
        var nodes = [];
        (function walk(el, depth){
          if (!el || depth > 2 || nodes.length >= 12) return;
          var b = el.getBoundingClientRect();
          nodes.push([String(el.id || el.className || el.tagName).slice(0, 30), Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)]);
          for (var i = 0; i < el.children.length && i < 4; i++) walk(el.children[i], depth + 1);
        })(document.body, 0);
        return JSON.stringify({
          title: document.title || '',
          body: document.body ? document.body.innerText.length : 0,
          ready: document.readyState,
          scripts: document.scripts.length,
          width: vw,
          height: vh,
          ua: navigator.userAgent,
          scroll: [window.scrollX, window.scrollY],
          bodyFix: bodyFix,
          repaired: repaired,
          vhFix: vhFix,
          vh: vhUnit,
          dvh: dvh,
          rootRect: rectOf(document.documentElement),
          bodyRect: rectOf(document.body),
          bodyScroll: document.body ? [document.body.scrollHeight, document.body.offsetHeight] : null,
          firstChild: first ? [String(first.id || first.className || first.tagName).slice(0, 60), rectOf(first)] : null,
          nodes: nodes,
          bodyBg: document.body ? getComputedStyle(document.body).backgroundColor : '',
          bodyColor: document.body ? getComputedStyle(document.body).color : '',
          desktop: (function(){ var d=document.getElementById('desktop'); if(!d) return null; var s=getComputedStyle(d),b=d.getBoundingClientRect(); return [s.display,s.visibility,s.opacity,b.x,b.y,b.width,b.height]; })(),
          atCenter: e ? (e.id || (typeof e.className === 'string' ? e.className : '') || e.tagName) : '',
          text: document.body ? document.body.innerText.slice(0, 200).replace(/\s+/g, ' ') : '',
          url: location.href
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

private fun openExternal(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
