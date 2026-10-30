package com.ugport.web

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ugport.BuildConfig
import com.ugport.network.UgreenApi

/**
 * WebView 的统一配置入口。
 *
 * 安全边界：只有 [isTrustedHost] 认可的主机才允许 JS 桥、相机/麦克风与登录脚本；
 * 文件访问和内容访问关闭，混合内容只允许兼容模式。
 *
 * 缓存策略：固定 [WebSettings.LOAD_NO_CACHE]。端口 WebUI 的 HTML 不带版本号，
 * 命中旧缓存会出现「只剩骨架、脚本不执行」的白屏。
 *
 * UA 与视口由 [applyUaMode] 幂等设置，切换端口复用同一个 WebView 也不会串模式。
 */
object WebViewConfigurator {

    private val ERROR_HOOK_SCRIPT = """
        (function(){
          if (window.__ugErrors) return;
          window.__ugErrors = [];
          window.addEventListener('error', function(e){
            try { window.__ugErrors.push((e.message || '') + ' @' + (e.filename || '')); } catch (_) {}
          }, true);
          window.addEventListener('unhandledrejection', function(e){
            try { window.__ugErrors.push('rejection: ' + e.reason); } catch (_) {}
          });
        })();
    """.trimIndent()

    /** 系统 WebView 默认 UA（移动端），取一次即可。 */
    private var defaultUa: String? = null

    /**
     * @param mediaEnabled 端口是否允许后台播放。关闭时不注入媒体探测脚本，
     *   页面只在前台正常出声，不产生媒体桥、轮询和前台服务开销。
     */
    fun configure(webView: WebView, desktopUa: Boolean, mediaEnabled: Boolean = true) {
        // 升级后清理一次历史缓存：之前版本的缓存里可能残留不可用的脚本响应，
        // 表现为页面只有菜单骨架、正文全空。
        val prefs = webView.context.getSharedPreferences("ugport_webview", Context.MODE_PRIVATE)
        if (prefs.getInt("cache_cleared_version", 0) != BuildConfig.VERSION_CODE) {
            webView.clearCache(true)
            prefs.edit().putInt("cache_cleared_version", BuildConfig.VERSION_CODE).apply()
        }
        webView.setBackgroundColor(Color.WHITE)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            // UA 与视口模式由 applyUaMode 统一设置
            // 端口 WebUI 的静态资源带版本号但 HTML 不带，缓存命中旧资源会导致
            // 页面只剩静态骨架（脚本全部未执行），因此固定为无缓存。
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        applyUaMode(webView, desktopUa)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        // 尽早收集页面脚本错误，便于排查“页面空白但无报错”的情况
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, ERROR_HOOK_SCRIPT, setOf("*"))
                // 媒体探测脚本尽早注入，才能捕获页面初始化阶段的播放事件
                if (mediaEnabled) {
                    WebViewCompat.addDocumentStartJavaScript(webView, MediaHookScript.inject(), setOf("*"))
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    /**
     * 幂等设置页面的 UA 与视口：WebScreen 会复用同一个 WebView，
     * 切换端口后必须能在移动端 / 桌面端之间来回切换。
     */
    fun applyUaMode(webView: WebView, desktopUa: Boolean) {
        val settings = webView.settings
        val ua = if (desktopUa) {
            UgreenApi.DESKTOP_UA
        } else {
            defaultUa ?: WebSettings.getDefaultUserAgent(webView.context).also { defaultUa = it }
        }
        if (settings.userAgentString != ua) settings.userAgentString = ua
        settings.useWideViewPort = desktopUa
        settings.loadWithOverviewMode = desktopUa
    }

    fun isTrustedHost(host: String?, baseUrl: String?): Boolean {
        val value = host?.lowercase() ?: return false
        if (value == "ug.link" || value.endsWith(".ug.link")) return true
        if (value.endsWith(".ugdocker.link")) return true
        if (value.endsWith(".ugnas.com") || value.endsWith(".ugreentest.com")) return true

        val baseHost = runCatching { Uri.parse(baseUrl).host }.getOrNull()?.lowercase()
        if (!baseHost.isNullOrBlank() && (value == baseHost || value.endsWith(".$baseHost"))) return true

        if (value == "localhost") return true
        if (value.startsWith("127.") || value.startsWith("10.") || value.startsWith("192.168.")) return true
        if (value.startsWith("172.")) {
            val second = value.split('.').getOrNull(1)?.toIntOrNull() ?: 0
            if (second in 16..31) return true
        }
        return false
    }
}
