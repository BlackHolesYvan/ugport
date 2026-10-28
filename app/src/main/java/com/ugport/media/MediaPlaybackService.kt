package com.ugport.media

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ugport.UgPortApp
import com.ugport.network.Diagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台媒体服务：只负责承载 MediaSession、通知、音频焦点与唤醒锁，
 * 真正的音频仍由 Activity 里的 WebView 播放。
 *
 * 服务的生命周期由 PlaybackHub 决定：本服务不会因为 ENDED/NONE/ERROR 自己 stopSelf，
 * 否则章节结束时会把 MediaSession 和通知一起拆掉，后台又无法重新 startForegroundService。
 */
class MediaPlaybackService : Service() {

    private lateinit var hub: PlaybackHub
    private lateinit var session: MediaSession
    private lateinit var focus: AudioFocusController
    private lateinit var diagnostics: Diagnostics

    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var noisyReceiver: BroadcastReceiver? = null
    private var pausedByFocusLoss = false
    private var resumeOnGain = false
    private var lastMetadataKey: String? = null
    private var lastNotificationKey: String? = null

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            pausedByFocusLoss = false
            resumeOnGain = false
            hub.command(PlaybackCommand.PLAY)
        }

        override fun onPause() {
            pausedByFocusLoss = false
            resumeOnGain = false
            hub.command(PlaybackCommand.PAUSE)
        }

        override fun onStop() {
            pausedByFocusLoss = false
            resumeOnGain = false
            hub.command(PlaybackCommand.STOP)
        }

        override fun onSeekTo(pos: Long) {
            hub.command(PlaybackCommand.SEEK, pos)
        }

        override fun onSkipToNext() {
            hub.command(PlaybackCommand.NEXT)
        }

        override fun onSkipToPrevious() {
            hub.command(PlaybackCommand.PREVIOUS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        hub = (application as UgPortApp).container.playback
        diagnostics = (application as UgPortApp).container.diagnostics
        MediaNotification.ensureChannel(this)
        log("service create")

        session = MediaSession(this, "UgPortPlayback").apply {
            setCallback(sessionCallback)
            isActive = true
        }
        focus = AudioFocusController(this, ::onFocusChange)
        registerNoisyReceiver()
        startForegroundCompat(hub.state.value)
        if (!hub.state.value.hasMedia) {
            detail("service create without media, stop")
            stopSelf()
            return
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { created ->
            created.launch {
                hub.state.collect { snapshot -> render(snapshot) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { detail("command action=$it") }
        when (intent?.action) {
            ACTION_PLAY -> hub.command(PlaybackCommand.PLAY)
            ACTION_PAUSE -> hub.command(PlaybackCommand.PAUSE)
            ACTION_STOP -> hub.command(PlaybackCommand.STOP)
            ACTION_NEXT -> hub.command(PlaybackCommand.NEXT)
            ACTION_PREVIOUS -> hub.command(PlaybackCommand.PREVIOUS)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        log("task removed, stop")
        hub.command(PlaybackCommand.STOP)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        log("service destroy")
        scope?.cancel()
        scope = null
        releaseWakeLock()
        releaseWifiLock()
        runCatching { focus.abandon() }
        runCatching {
            session.isActive = false
            session.release()
        }
        unregisterNoisyReceiver()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        MediaNotification.cancel(this)
        hub.onServiceStopped()
        super.onDestroy()
    }

    private fun startForegroundCompat(snapshot: PlaybackSnapshot) {
        val notification = MediaNotification.build(this, session, snapshot)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                MediaNotification.NOTIFICATION_ID,
                notification,
                type,
            )
        }.onFailure {
            log("startForeground failed ${it.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun render(snapshot: PlaybackSnapshot) {
        if (snapshot.phase == PlaybackPhase.NONE) {
            // 生命周期由 Hub 决定，这里不要 stopSelf
            return
        }

        val metadataKey = "${snapshot.title}|${snapshot.artist}|${snapshot.album}|${snapshot.durationMs}"
        if (metadataKey != lastMetadataKey) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(
                        MediaMetadata.METADATA_KEY_TITLE,
                        snapshot.title.ifBlank { getString(com.ugport.R.string.notification_unknown_track) },
                    )
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.artist.ifBlank { null })
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, snapshot.album.ifBlank { null })
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs)
                    .build()
            )
            lastMetadataKey = metadataKey
        }

        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            (if (snapshot.durationMs > 0) PlaybackState.ACTION_SEEK_TO else 0L) or
            (if (snapshot.canPrev) PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L) or
            (if (snapshot.canNext) PlaybackState.ACTION_SKIP_TO_NEXT else 0L)
        val state = when (snapshot.phase) {
            PlaybackPhase.PLAYING -> PlaybackState.STATE_PLAYING
            PlaybackPhase.BUFFERING -> PlaybackState.STATE_BUFFERING
            PlaybackPhase.PAUSED -> PlaybackState.STATE_PAUSED
            // ENDED/ERROR 也用 PAUSED，保住系统媒体卡片；部分 ROM 会把 STOPPED/ERROR 收起
            PlaybackPhase.ENDED, PlaybackPhase.ERROR -> PlaybackState.STATE_PAUSED
            PlaybackPhase.NONE -> PlaybackState.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    state,
                    snapshot.positionMs,
                    if (snapshot.phase == PlaybackPhase.PLAYING) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build()
        )

        val notificationKey = listOf(
            snapshot.title,
            snapshot.artist,
            snapshot.album,
            snapshot.phase.name,
            snapshot.playing,
            snapshot.canPrev,
            snapshot.canNext,
            snapshot.durationMs > 0,
        ).joinToString("|")
        if (notificationKey != lastNotificationKey) {
            MediaNotification.update(this, MediaNotification.build(this, session, snapshot))
            lastNotificationKey = notificationKey
        }

        if (snapshot.playing) {
            pausedByFocusLoss = false
            focus.request()
            acquireWakeLock()
            acquireWifiLock()
        } else {
            releaseWakeLock()
            releaseWifiLock()
            if (!pausedByFocusLoss) focus.abandon()
        }
    }

    private fun onFocusChange(event: FocusEvent) {
        detail("audio focus $event")
        when (event) {
            FocusEvent.GAIN -> {
                if (resumeOnGain) {
                    resumeOnGain = false
                    pausedByFocusLoss = false
                    hub.command(PlaybackCommand.PLAY)
                }
            }
            FocusEvent.LOSS -> {
                pausedByFocusLoss = true
                resumeOnGain = false
                hub.command(PlaybackCommand.PAUSE)
            }
            FocusEvent.LOSS_TRANSIENT -> {
                pausedByFocusLoss = true
                resumeOnGain = true
                hub.command(PlaybackCommand.PAUSE)
            }
        }
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java) ?: return
        val lock = wakeLock ?: manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        ).apply {
            setReferenceCounted(false)
            wakeLock = this
        }
        if (!lock.isHeld) {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            detail("wake lock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
                detail("wake lock released")
            }
        }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val manager = getSystemService(WifiManager::class.java) ?: return
        val lock = wifiLock ?: run {
            // 音频码率低，HIGH_PERF 足够避免 Wi-Fi 休眠断流；
            // FULL_LOW_LATENCY 是给游戏/VR 用的，会明显增加功耗
            manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                wifiLock = this
            }
        }
        if (!lock.isHeld) {
            runCatching { lock.acquire() }.onSuccess { detail("wifi lock acquired") }
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
                detail("wifi lock released")
            }
        }
        wifiLock = null
    }

    private fun registerNoisyReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    log("audio becoming noisy")
                    pausedByFocusLoss = false
                    resumeOnGain = false
                    hub.command(PlaybackCommand.PAUSE)
                }
            }
        }
        noisyReceiver = receiver
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun unregisterNoisyReceiver() {
        noisyReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        noisyReceiver = null
    }

    private fun log(message: String) {
        diagnostics.event(message, TAG)
    }

    private fun detail(message: String) {
        diagnostics.detail(message, TAG)
    }

    companion object {
        const val ACTION_PLAY = "com.ugport.media.PLAY"
        const val ACTION_PAUSE = "com.ugport.media.PAUSE"
        const val ACTION_STOP = "com.ugport.media.STOP"
        const val ACTION_NEXT = "com.ugport.media.NEXT"
        const val ACTION_PREVIOUS = "com.ugport.media.PREVIOUS"

        private const val TAG = "UgPortMedia"
        private const val WAKE_LOCK_TAG = "ugport:playback"
        private const val WIFI_LOCK_TAG = "ugport:playback:wifi"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
