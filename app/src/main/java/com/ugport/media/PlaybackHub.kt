package com.ugport.media

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    RECOVER,
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

    /** 首次进入播放状态时触发，用于申请通知权限/电池优化提示。 */
    var onPlaybackStarted: (() -> Unit)? = null

    private var controller: WebMediaController? = null
    private var serviceStarted = false
    private var lastServiceFailureAt = 0L
    private var polling = false
    private var pollIntervalMs = POLL_INTERVAL_MS
    private var lastTrustLogAt = 0L

    private var pendingStop: Runnable? = null
    private var endedStop: Runnable? = null

    private var lastProgressAt = 0L
    private var lastPositionMs = -1L
    private var lastReportAt = 0L
    private var stallStage = 0
    private var lastActionAt = 0L
    private var lastRecoverAt = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            // 页面播放时每秒都有 timeupdate 上报，没必要再每 5 秒执行一次 JS 探针；
            // 只有长时间没有任何上报时才主动补一次，降低后台 CPU 与跨桥开销
            val now = SystemClock.elapsedRealtime()
            if (lastReportAt == 0L || now - lastReportAt >= PROBE_QUIET_MS) {
                controller?.evaluate(MediaHookScript.probe())
            }
            checkWatchdog()
            main.postDelayed(this, pollIntervalMs)
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
            clearState()
        }
    }

    /**
     * 页面导航/加载时不要立即拆前台服务：章节切换可能是一次整页导航，
     * 服务一停，后台就无法重新 startForegroundService。
     */
    fun onPageStarted() {
        main.post {
            val current = _state.value
            if (!current.hasMedia) {
                if (serviceStarted) clearState()
                return@post
            }
            if (pendingStop != null &&
                (current.phase == PlaybackPhase.PAUSED || current.phase == PlaybackPhase.BUFFERING)
            ) {
                return@post
            }
            cancelTerminalStop()
            cancelEndedStop()
            // 原本在播放时保持 BUFFERING：页面导航/章节切换期间继续持有 wake/wifi/focus，
            // 避免后台切章时 CPU/Wi-Fi 立刻睡眠导致新章节加载不出来
            val keepLocks = current.playing ||
                current.phase == PlaybackPhase.ENDED ||
                current.phase == PlaybackPhase.ERROR
            val frozenPhase = if (keepLocks) PlaybackPhase.BUFFERING else PlaybackPhase.PAUSED
            _state.value = current.copy(phase = frozenPhase)
            resetWatchdog()
            startPolling()
            scheduleStop(PAGE_NAV_GRACE_MS, "navigation")
            detail("media: page started, keep service for next page")
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
            clearState()
        }
    }

    /** 回到前台时，如果服务曾被后台限制停掉，在前台重新拉起来。 */
    fun onHostResumed() {
        main.post {
            val current = _state.value
            if (!current.hasMedia) return@post
            // 不要在这里取消 pendingStop：它可能是页面导航/终止状态的宽限计时，
            // 页面重新上报 PLAYING/PAUSED 时自然会取消；盲目取消会让 BUFFERING 冻结状态永久停留。
            if (current.phase != PlaybackPhase.ENDED) cancelEndedStop()
            if (!serviceStarted) ensureService(force = true)
            if (current.playing) startPolling()
            detail("media: host resumed phase=${current.phase} service=$serviceStarted")
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
            val now = SystemClock.elapsedRealtime()
            lastReportAt = now

            if (previous.canPrev != snapshot.canPrev || previous.canNext != snapshot.canNext) {
                detail("media: prev=${snapshot.canPrev} next=${snapshot.canNext}")
            }

            when (snapshot.phase) {
                PlaybackPhase.PLAYING, PlaybackPhase.BUFFERING -> {
                    cancelTerminalStop()
                    cancelEndedStop()
                    _state.value = snapshot
                    ensureService()
                    startPolling()
                    updateProgress(snapshot, previous, now)
                    if (!previous.playing) {
                        // 暂停后恢复不重复写；换曲/换章/首次播放才记一条
                        if (!previous.hasMedia ||
                            previous.phase == PlaybackPhase.ENDED ||
                            previous.title != snapshot.title
                        ) {
                            log("media: playing ${snapshot.title.take(40)}")
                        }
                        onPlaybackStarted?.invoke()
                    }
                }

                PlaybackPhase.PAUSED -> {
                    cancelTerminalStop()
                    cancelEndedStop()
                    _state.value = snapshot
                    stopPolling()
                    resetWatchdog()
                    if (previous.playing) log("media: paused")
                }

                PlaybackPhase.ENDED -> {
                    cancelTerminalStop()
                    _state.value = snapshot
                    if (!serviceStarted) ensureService()
                    startPolling(ENDED_POLL_INTERVAL_MS)
                    resetWatchdog()
                    if (previous.phase != PlaybackPhase.ENDED) {
                        cancelEndedStop()
                        scheduleEndedStop()
                        log("media: ended, keep notification for next chapter")
                    }
                }

                PlaybackPhase.ERROR -> {
                    cancelEndedStop()
                    _state.value = snapshot
                    startPolling()
                    resetWatchdog()
                    if (previous.phase != PlaybackPhase.ERROR) {
                        log("media: error, try recover once")
                        scheduleErrorRecover()
                    }
                    scheduleStop(ERROR_GRACE_MS, "error")
                }

                PlaybackPhase.NONE -> {
                    if (previous.hasMedia) {
                        cancelEndedStop()
                        if (serviceStarted || previous.playing) {
                            // 冻结上一次的元数据，通知不要立刻变空；
                            // 原本在播放时保持 BUFFERING，章节切换期间不释放锁
                            val keepLocks = previous.playing ||
                                previous.phase == PlaybackPhase.ENDED ||
                                previous.phase == PlaybackPhase.ERROR
                            val frozenPhase = if (keepLocks) {
                                PlaybackPhase.BUFFERING
                            } else {
                                PlaybackPhase.PAUSED
                            }
                            _state.value = previous.copy(phase = frozenPhase)
                            startPolling()
                            resetWatchdog()
                            scheduleStop(NONE_GRACE_MS, "none")
                            detail("media: no media, keep service briefly")
                        } else {
                            clearState()
                        }
                    } else {
                        clearState()
                    }
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
                PlaybackCommand.RECOVER -> """{"cmd":"recover"}"""
            }
            current.evaluate(MediaHookScript.command(json))
            applyOptimistic(command, positionMs)
        }
    }

    fun stopAll() {
        main.post {
            controller?.evaluate(MediaHookScript.command("""{"cmd":"pause"}"""))
            controller = null
            clearState()
        }
    }

    fun onServiceStopped() {
        serviceStarted = false
        log("media: service stopped")
    }

    private fun applyOptimistic(command: PlaybackCommand, positionMs: Long?) {
        val current = _state.value
        if (!current.hasMedia) return
        when (command) {
            PlaybackCommand.PLAY -> {
                cancelTerminalStop()
                cancelEndedStop()
                if (!current.playing) {
                    _state.value = current.copy(phase = PlaybackPhase.PLAYING)
                    ensureService()
                    startPolling()
                }
                resetWatchdog()
            }

            PlaybackCommand.PAUSE -> {
                cancelTerminalStop()
                cancelEndedStop()
                _state.value = current.copy(phase = PlaybackPhase.PAUSED)
                stopPolling()
                resetWatchdog()
            }

            PlaybackCommand.STOP -> clearState()

            PlaybackCommand.TOGGLE -> {
                cancelTerminalStop()
                cancelEndedStop()
                if (current.playing) {
                    _state.value = current.copy(phase = PlaybackPhase.PAUSED)
                    stopPolling()
                } else {
                    _state.value = current.copy(phase = PlaybackPhase.PLAYING)
                    ensureService()
                    startPolling()
                }
                resetWatchdog()
            }

            PlaybackCommand.SEEK -> {
                if (positionMs != null) {
                    _state.value = current.copy(positionMs = positionMs.coerceAtLeast(0L))
                    lastProgressAt = SystemClock.elapsedRealtime()
                    lastPositionMs = _state.value.positionMs
                    stallStage = 0
                }
            }

            PlaybackCommand.NEXT, PlaybackCommand.PREVIOUS -> {
                // 曲目信息只有页面知道，不做乐观修改，等页面加载新曲目后自然上报
                cancelTerminalStop()
                cancelEndedStop()
                lastProgressAt = SystemClock.elapsedRealtime()
                lastPositionMs = -1L
                stallStage = 0
            }

            PlaybackCommand.RECOVER -> {
                // 只下发恢复命令，不改状态
            }
        }
    }

    private fun updateProgress(snapshot: PlaybackSnapshot, previous: PlaybackSnapshot, now: Long) {
        val phaseChanged = previous.phase != snapshot.phase
        val moved = snapshot.positionMs > lastPositionMs + 500 ||
            snapshot.positionMs < lastPositionMs - 1000
        if (phaseChanged || lastProgressAt == 0L || moved) {
            lastProgressAt = now
            lastPositionMs = snapshot.positionMs
            stallStage = 0
        }
    }

    private fun checkWatchdog() {
        val snapshot = _state.value
        val now = SystemClock.elapsedRealtime()

        if (snapshot.playing && lastReportAt > 0 && now - lastReportAt > REPORT_SILENCE_MS) {
            detail("media: page silent ${(now - lastReportAt) / 1000}s")
            lastReportAt = now
        }

        if (!snapshot.playing) return
        if (snapshot.phase == PlaybackPhase.PLAYING && snapshot.durationMs <= 0L) return
        if (lastProgressAt <= 0L) return

        val threshold = if (snapshot.phase == PlaybackPhase.BUFFERING) {
            STALL_BUFFER_MS
        } else {
            STALL_PLAY_MS
        }
        val stalledFor = now - lastProgressAt
        if (stalledFor < threshold) return

        if (stallStage == 0) {
            detail("media: no progress ${stalledFor / 1000}s, retry play")
            command(PlaybackCommand.PLAY)
            stallStage = 1
            lastActionAt = now
        } else if (stallStage == 1 &&
            now - lastActionAt >= STALL_RECOVER_MS &&
            now - lastRecoverAt >= RECOVER_THROTTLE_MS
        ) {
            log("media: still no progress, recover")
            command(PlaybackCommand.RECOVER)
            stallStage = 2
            lastActionAt = now
            lastRecoverAt = now
        }
    }

    private fun resetWatchdog() {
        lastProgressAt = 0L
        lastPositionMs = -1L
        lastReportAt = 0L
        stallStage = 0
        lastActionAt = 0L
        lastRecoverAt = 0L
    }

    private fun scheduleStop(delayMs: Long, reason: String) {
        if (pendingStop != null) return
        val task = Runnable {
            pendingStop = null
            detail("media: stop after grace ($reason)")
            clearState()
        }
        pendingStop = task
        main.postDelayed(task, delayMs)
    }

    private fun cancelTerminalStop() {
        pendingStop?.let { main.removeCallbacks(it) }
        pendingStop = null
    }

    private fun scheduleEndedStop() {
        if (endedStop != null) return
        val task = Runnable {
            endedStop = null
            detail("media: ended idle timeout")
            clearState()
        }
        endedStop = task
        main.postDelayed(task, ENDED_IDLE_STOP_MS)
    }

    private fun cancelEndedStop() {
        endedStop?.let { main.removeCallbacks(it) }
        endedStop = null
    }

    private fun scheduleErrorRecover() {
        main.postDelayed({
            if (_state.value.phase == PlaybackPhase.ERROR) {
                detail("media: error recover")
                command(PlaybackCommand.RECOVER)
            }
        }, ERROR_RECOVER_DELAY_MS)
    }

    private fun clearState() {
        cancelTerminalStop()
        cancelEndedStop()
        stopPolling()
        resetWatchdog()
        _state.value = PlaybackSnapshot()
        stopService()
    }

    private fun ensureService(force: Boolean = false) {
        if (serviceStarted) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastServiceFailureAt < SERVICE_RETRY_MS) return
        serviceStarted = true
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, MediaPlaybackService::class.java),
            )
        } catch (e: Exception) {
            serviceStarted = false
            lastServiceFailureAt = SystemClock.elapsedRealtime()
            log("media: start service failed ${e.javaClass.simpleName}")
        }
    }

    private fun stopService() {
        if (!serviceStarted) return
        serviceStarted = false
        runCatching { appContext.stopService(Intent(appContext, MediaPlaybackService::class.java)) }
    }

    private fun startPolling(intervalMs: Long = POLL_INTERVAL_MS) {
        pollIntervalMs = intervalMs
        if (polling) return
        polling = true
        main.postDelayed(pollRunnable, intervalMs)
    }

    private fun stopPolling() {
        polling = false
        pollIntervalMs = POLL_INTERVAL_MS
        main.removeCallbacks(pollRunnable)
    }

    private fun logTrustRejected() {
        val now = System.currentTimeMillis()
        if (now - lastTrustLogAt < 10_000) return
        lastTrustLogAt = now
        log("media: ignore state from untrusted page")
    }

    private fun log(message: String) {
        diagnostics.event(message, TAG)
    }

    private fun detail(message: String) {
        diagnostics.detail(message, TAG)
    }

    private companion object {
        const val TAG = "UgPortMedia"
        const val POLL_INTERVAL_MS = 5000L
        const val ENDED_POLL_INTERVAL_MS = 10_000L
        const val NONE_GRACE_MS = 30_000L
        const val PAGE_NAV_GRACE_MS = 30_000L
        const val ERROR_GRACE_MS = 30_000L
        const val ERROR_RECOVER_DELAY_MS = 5_000L
        const val ENDED_IDLE_STOP_MS = 30 * 60_000L
        const val STALL_PLAY_MS = 20_000L
        const val STALL_BUFFER_MS = 30_000L
        const val STALL_RECOVER_MS = 15_000L
        const val RECOVER_THROTTLE_MS = 60_000L
        const val REPORT_SILENCE_MS = 45_000L
        const val PROBE_QUIET_MS = 15_000L
        const val SERVICE_RETRY_MS = 30_000L
    }
}
