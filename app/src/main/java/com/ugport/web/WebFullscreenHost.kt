package com.ugport.web

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ugport.network.Diagnostics

/** 全屏方向策略：宽高比决定横竖，取不到时交给重力感应。 */
internal enum class FullscreenOrientation { LANDSCAPE, PORTRAIT, SENSOR }

/** 宽高比落在 0.9~1.1 之间视为方形或未知，不做强制方向。 */
internal fun resolveFullscreenOrientation(aspectRatio: Float?): FullscreenOrientation {
    val value = aspectRatio?.takeIf { it.isFinite() && it > 0f }
        ?: return FullscreenOrientation.SENSOR
    return when {
        value >= 1.1f -> FullscreenOrientation.LANDSCAPE
        value <= 0.9f -> FullscreenOrientation.PORTRAIT
        else -> FullscreenOrientation.SENSOR
    }
}

/** 解析 evaluateJavascript 返回的 `"1280x720"`（JSON 字符串带引号）。 */
internal fun parseVideoAspect(raw: String?): Float? {
    val text = raw?.trim()?.trim('"')?.trim().orEmpty()
    if (text.isEmpty() || text == "null") return null
    val parts = text.split('x')
    if (parts.size != 2) return null
    val width = parts[0].toFloatOrNull() ?: return null
    val height = parts[1].toFloatOrNull() ?: return null
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
    return width / height
}

/**
 * 网页视频全屏宿主。
 *
 * WebView 的 `onShowCustomView` 只负责把视频渲染 View 交出来，宿主必须自己挂到
 * 窗口最上层并隐藏系统栏。这里挂在 Activity 的 decorView 上，既能盖住 Compose
 * 顶栏和系统栏，也不参与 Compose 布局，避免重组影响 WebView。
 *
 * 所有退出路径（返回键、页面退出、导航、渲染进程崩溃、页面销毁、Activity 销毁）
 * 都走 [hide]，保证系统栏、方向与常亮只有一个恢复出口。
 */
class WebFullscreenHost(
    private val activity: Activity?,
    private val diagnostics: Diagnostics,
) {

    /** 只在主线程读写：WebChromeClient、BackHandler、生命周期回调都在主线程。 */
    var isActive: Boolean = false
        private set

    private var container: FrameLayout? = null
    private var customView: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var orientationChanged = false
    private var keepScreenOnAdded = false
    private var navContrastSaved: Boolean? = null

    fun show(webView: WebView, view: View, callback: WebChromeClient.CustomViewCallback) {
        val act = activity
        val window = act?.window
        val decor = window?.decorView as? ViewGroup
        if (act == null || window == null || decor == null ||
            act.isFinishing || act.isDestroyed || !decor.isAttachedToWindow
        ) {
            diagnostics.event("fullscreen: rejected (activity unavailable)")
            safeHidden(callback)
            return
        }
        // Chromium 正常不会连续 show；出现了就只保留第一份，避免两个 callback 悬挂
        if (customView != null) {
            diagnostics.event("fullscreen: duplicate show ignored")
            safeHidden(callback)
            return
        }
        // WebView 交出的 View 可能还挂在内部容器上，直接 addView 会抛
        // "The specified child already has a parent"
        val detached = runCatching {
            (view.parent as? ViewGroup)?.let { parent ->
                diagnostics.detail("fullscreen: reparent from ${parent.javaClass.simpleName}")
                parent.removeView(view)
            }
        }.isSuccess
        if (!detached) {
            diagnostics.event("fullscreen: reparent failed")
            safeHidden(callback)
            return
        }

        val box = FrameLayout(act).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }
        val attached = runCatching {
            box.addView(view, matchParent())
            decor.addView(box, matchParent())
        }.isSuccess
        if (!attached) {
            diagnostics.event("fullscreen: attach failed")
            runCatching { box.removeAllViews() }
            safeHidden(callback)
            return
        }

        container = box
        customView = view
        this.callback = callback
        isActive = true

        keepScreenOnAdded =
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0
        if (keepScreenOnAdded) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        runCatching {
            WindowCompat.getInsetsController(window, decor).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }.onFailure {
            diagnostics.event("fullscreen: hide bars failed (${it.javaClass.simpleName})")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            navContrastSaved = window.isNavigationBarContrastEnforced
            window.isNavigationBarContrastEnforced = false
        }

        savedOrientation = act.requestedOrientation
        orientationChanged = true
        // 先跟随重力，等宽高比探针回来再收敛到横屏或竖屏
        applyOrientation(FullscreenOrientation.SENSOR)
        probeVideoAspect(webView)

        diagnostics.event("fullscreen: enter ${view.javaClass.simpleName}")
    }

    /**
     * 唯一退出出口。返回 true 表示确实拆掉了一次全屏。
     *
     * @param notifyWebView 页面主动退出（onHideCustomView）时传 false：WebView 已经
     *   知道全屏结束，再回调 onCustomViewHidden 属于重复通知。
     */
    fun hide(reason: String, notifyWebView: Boolean = true): Boolean {
        val view = customView
        val box = container
        if (view == null && box == null) return false
        val cb = callback
        // 先清空字段：onCustomViewHidden 可能同步触发 onHideCustomView 重入
        customView = null
        callback = null
        container = null
        isActive = false

        // 先只摘掉视频 View，黑色容器继续遮住 WebView，避免退出瞬间白闪
        runCatching {
            view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }.onFailure {
            diagnostics.event("fullscreen: detach error (${it.javaClass.simpleName})")
        }

        // 通知 WebView 恢复内联渲染；字段已置空，重入为 no-op
        if (notifyWebView) {
            runCatching { cb?.onCustomViewHidden() }
                .onFailure {
                    diagnostics.event("fullscreen: callback error (${it.javaClass.simpleName})")
                }
        }

        runCatching {
            box?.removeAllViews()
            box?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }.onFailure {
            diagnostics.event("fullscreen: detach error (${it.javaClass.simpleName})")
        }

        restoreWindow()
        diagnostics.event("fullscreen: exit ($reason)")
        return true
    }

    private fun restoreWindow() {
        val act = activity ?: return
        val window = act.window ?: return
        runCatching {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            navContrastSaved?.let { window.isNavigationBarContrastEnforced = it }
            navContrastSaved = null
        }
        if (orientationChanged) {
            runCatching { act.requestedOrientation = savedOrientation }
            orientationChanged = false
        }
        if (keepScreenOnAdded) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            keepScreenOnAdded = false
        }
    }

    private fun applyOrientation(policy: FullscreenOrientation) {
        val act = activity ?: return
        val target = when (policy) {
            FullscreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            FullscreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            FullscreenOrientation.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        if (act.requestedOrientation == target) return
        runCatching { act.requestedOrientation = target }
        diagnostics.detail("fullscreen: orientation ${policy.name.lowercase()} -> $target")
    }

    private fun probeVideoAspect(webView: WebView) {
        runCatching {
            webView.evaluateJavascript(VIDEO_ASPECT_PROBE) { raw ->
                if (!isActive) return@evaluateJavascript
                val aspect = parseVideoAspect(raw)
                val policy = resolveFullscreenOrientation(aspect)
                diagnostics.detail("fullscreen: aspect=$aspect -> ${policy.name}")
                applyOrientation(policy)
            }
        }.onFailure {
            diagnostics.detail("fullscreen: aspect probe failed (${it.javaClass.simpleName})")
        }
    }

    private fun safeHidden(cb: WebChromeClient.CustomViewCallback) {
        runCatching { cb.onCustomViewHidden() }
            .onFailure { diagnostics.event("fullscreen: callback error (${it.javaClass.simpleName})") }
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private companion object {
        /**
         * 取正在播放/可见面积最大的 video 的原始尺寸。
         * Shadow DOM 或跨 iframe 的播放器取不到，返回空串后由重力感应决定方向。
         */
        val VIDEO_ASPECT_PROBE = """
            (function () {
              try {
                var list = document.querySelectorAll('video');
                var best = null, bestArea = 0;
                for (var i = 0; i < list.length; i++) {
                  var v = list[i];
                  if (!v.videoWidth || !v.videoHeight) continue;
                  var r = v.getBoundingClientRect();
                  var area = Math.max(1, r.width * r.height) * (v.paused ? 1 : 2);
                  if (area > bestArea) { bestArea = area; best = v; }
                }
                return best ? best.videoWidth + 'x' + best.videoHeight : '';
              } catch (e) { return ''; }
            })();
        """.trimIndent()
    }
}
