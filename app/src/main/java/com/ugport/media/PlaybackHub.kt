package com.ugport.media

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ugport.network.Diagnostics
import com.ugport.web.MediaHookScript
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface WebMediaController {
    fun evaluate(script: String)
    fun isTrustedPage(): Boolean
}

enum class PlaybackCommand {
    PLAY,
    PAUSE,
    TOGGLE,
    STOP,
    SEEK,
    NEXT,
    PREVIOUS,
}

/**
 * 页面播放状态的唯一汇合点：JS 上报、系统媒体控制、通知按钮都走这里。
 * 所有状态变更都切到主线程，避免 WebView 的 JavaBridge 线程直接操作 UI/服务。
 */
class PlaybackHub(
    private val appContext: Context,
    private val diagnostics: Diagnostics,
) {

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PlaybackSnapshot())
    val state: StateFlow<PlaybackSnapshot> = _state.asStateFlow()

    val isPlaying: Boolean
        get() = _state.value.playing

    /** 首次进入播放状态时触发，用于申请通知权限。 */
    var onPlaybackStarted: (() -> Unit)? = null

    private var controller: WebMediaController? = null
    private var serviceStarted = false
    private var polling = false
    private var lastTrustLogAt = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            controller?.evaluate(MediaHookScript.probe())
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun attach(controller: WebMediaController) {
        main.post {
            this.controller = controller
            controller.evaluate(MediaHookScript.inject())
            controller.evaluate(MediaHookScript.probe())
        }
    }

    fun detach(controller: WebMediaController? = null) {
        main.post {
            if (controller != null && this.controller !== controller) return@post
            this.controller = null
            stopPolling()
            _state.value = PlaybackSnapshot()
            stopService()
        }
    }

    fun onPageStarted() {
        main.post {
            stopPolling()
            if (_state.value.hasMedia || serviceStarted) {
                _state.value = PlaybackSnapshot()
                stopService()
            }
        }
    }

    fun onPageLoaded() {
        main.post {
            controller?.evaluate(MediaHookScript.inject())
            controller?.evaluate(MediaHookScript.probe())
        }
    }

    fun onRendererGone() {
        main.post {
            controller = null
            stopPolling()
            _state.value = PlaybackSnapshot()
            stopService()
        }
    }

    fun onJsState(raw: String) {
        val snapshot = MediaStateParser.parse(raw) ?: return
        main.post {
            val current = controller ?: return@post
            if (!current.isTrustedPage()) {
                logTrustRejected()
                return@post
            }
            val previous = _state.value
            _state.value = snapshot
            if (previous.canPrev != snapshot.canPrev || previous.canNext != snapshot.canNext) {
                diagnostics.log("media: prev=${snapshot.canPrev} next=${snapshot.canNext}")
            }
            when (snapshot.phase) {
                PlaybackPhase.NONE -> {
                    stopPolling()
                    stopService()
                }
                PlaybackPhase.ENDED, PlaybackPhase.ERROR -> {
                    stopPolling()
                    stopService()
                }
                PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING -> {
                    ensureService()
                    startPolling()
                    if (!previous.playing) {
                        diagnostics.log("media: playing ${snapshot.title.take(40)}")
                        onPlaybackStarted?.invoke()
                    }
                }
                PlaybackPhase.PAUSED -> {
                    stopPolling()
                    if (previous.playing) diagnostics.log("media: paused")
                }
            }
        }
    }

    fun command(command: PlaybackCommand, positionMs: Long? = null) {
        main.post {
            val current = controller ?: return@post
            val json = when (command) {
                PlaybackCommand.PLAY -> """{"cmd":"play"}"""
                PlaybackCommand.PAUSE -> """{"cmd":"pause"}"""
                PlaybackCommand.TOGGLE -> """{"cmd":"toggle"}"""
                PlaybackCommand.STOP -> """{"cmd":"stop"}"""
                PlaybackCommand.SEEK -> """{"cmd":"seek","pos":${positionMs ?: 0L}}"""
                PlaybackCommand.NEXT -> """{"cmd":"next"}"""
                PlaybackCommand.PREVIOUS -> """{"cmd":"prev"}"""
            }
            current.evaluate(MediaHookScript.command(json))
            applyOptimistic(command, positionMs)
        }
    }

    fun stopAll() {
        main.post {
            controller?.evaluate(MediaHookScript.command("""{"cmd":"pause"}"""))
            controller = null
            stopPolling()
            _state.value = PlaybackSnapshot()
            stopService()
        }
    }

    fun onServiceStopped() {
        serviceStarted = false
    }

    private fun applyOptimistic(command: PlaybackCommand, positionMs: Long?) {
        val current = _state.value
        if (!current.hasMedia) return
        when (command) {
            PlaybackCommand.PLAY -> {
                if (!current.playing) {
                    _state.value = current.copy(phase = PlaybackPhase.PLAYING)
                    ensureService()
                    startPolling()
                }
            }
            PlaybackCommand.PAUSE -> {
                _state.value = current.copy(phase = PlaybackPhase.PAUSED)
                stopPolling()
            }
            PlaybackCommand.STOP -> {
                _state.value = PlaybackSnapshot()
                stopPolling()
                stopService()
            }
            PlaybackCommand.TOGGLE -> {
                if (current.playing) {
                    _state.value = current.copy(phase = PlaybackPhase.PAUSED)
                    stopPolling()
                } else {
                    _state.value = current.copy(phase = PlaybackPhase.PLAYING)
                    ensureService()
                    startPolling()
                }
            }
            PlaybackCommand.SEEK -> {
                if (positionMs != null) {
                    _state.value = current.copy(positionMs = positionMs.coerceAtLeast(0L))
                }
            }
            PlaybackCommand.NEXT, PlaybackCommand.PREVIOUS -> {
                // 曲目信息只有页面知道，不做乐观修改，等页面加载新曲目后自然上报
            }
        }
    }

    private fun ensureService() {
        if (serviceStarted) return
        serviceStarted = true
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, MediaPlaybackService::class.java),
            )
        } catch (e: Exception) {
            serviceStarted = false
            diagnostics.log("media: start service failed ${e.javaClass.simpleName}")
        }
    }

    private fun stopService() {
        if (!serviceStarted) return
        serviceStarted = false
        runCatching { appContext.stopService(Intent(appContext, MediaPlaybackService::class.java)) }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        main.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        polling = false
        main.removeCallbacks(pollRunnable)
    }

    private fun logTrustRejected() {
        val now = System.currentTimeMillis()
        if (now - lastTrustLogAt < 10_000) return
        lastTrustLogAt = now
        diagnostics.log("media: ignore state from untrusted page")
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5000L
    }
}
