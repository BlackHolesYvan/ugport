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
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ugport.UgPortApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台媒体服务：只负责承载 MediaSession、通知、音频焦点与唤醒锁，
 * 真正的音频仍由 Activity 里的 WebView 播放。
 */
class MediaPlaybackService : Service() {

    private lateinit var hub: PlaybackHub
    private lateinit var session: MediaSession
    private lateinit var focus: AudioFocusController

    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var noisyReceiver: BroadcastReceiver? = null
    private var pausedByFocusLoss = false
    private var resumeOnGain = false

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
        MediaNotification.ensureChannel(this)

        session = MediaSession(this, "UgPortPlayback").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(sessionCallback)
            isActive = true
        }
        focus = AudioFocusController(this, ::onFocusChange)
        registerNoisyReceiver()
        startForegroundCompat(hub.state.value)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { created ->
            created.launch {
                hub.state.collect { snapshot -> render(snapshot) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        hub.command(PlaybackCommand.STOP)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        releaseWakeLock()
        runCatching { focus.abandon() }
        runCatching {
            session.isActive = false
            session.release()
        }
        unregisterNoisyReceiver()
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
            // 无法进入前台时主动结束，避免系统在超时后抛 RemoteServiceException
            stopSelf()
        }
    }

    private fun render(snapshot: PlaybackSnapshot) {
        if (snapshot.phase == PlaybackPhase.NONE) {
            stopPlayback()
            return
        }

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
            PlaybackPhase.ENDED -> PlaybackState.STATE_STOPPED
            PlaybackPhase.ERROR -> PlaybackState.STATE_ERROR
            PlaybackPhase.NONE -> PlaybackState.STATE_NONE
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    state,
                    snapshot.positionMs,
                    if (snapshot.playing) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .build()
        )

        MediaNotification.update(this, MediaNotification.build(this, session, snapshot))

        if (snapshot.playing) {
            pausedByFocusLoss = false
            focus.request()
            acquireWakeLock()
        } else {
            releaseWakeLock()
            if (!pausedByFocusLoss) focus.abandon()
        }

        if (snapshot.phase == PlaybackPhase.ENDED || snapshot.phase == PlaybackPhase.ERROR) {
            stopPlayback()
        }
    }

    private fun onFocusChange(event: FocusEvent) {
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

    private fun stopPlayback() {
        releaseWakeLock()
        focus.abandon()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun registerNoisyReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
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

    companion object {
        const val ACTION_PLAY = "com.ugport.media.PLAY"
        const val ACTION_PAUSE = "com.ugport.media.PAUSE"
        const val ACTION_STOP = "com.ugport.media.STOP"
        const val ACTION_NEXT = "com.ugport.media.NEXT"
        const val ACTION_PREVIOUS = "com.ugport.media.PREVIOUS"

        private const val WAKE_LOCK_TAG = "ugport:playback"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
