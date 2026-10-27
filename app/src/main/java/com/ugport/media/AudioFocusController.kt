package com.ugport.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

enum class FocusEvent {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
}

/** 音频焦点封装。Android 15+ 要求请求焦点时 App 处于前台或运行音频类前台服务。 */
class AudioFocusController(
    context: Context,
    private val onEvent: (FocusEvent) -> Unit,
) {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                onEvent(FocusEvent.GAIN)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                onEvent(FocusEvent.LOSS)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false
                onEvent(FocusEvent.LOSS_TRANSIENT)
            }
        }
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener(listener)
        .setWillPauseWhenDucked(true)
        .build()

    var hasFocus: Boolean = false
        private set

    fun request(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    fun abandon() {
        if (!hasFocus) return
        runCatching { audioManager.abandonAudioFocusRequest(request) }
        hasFocus = false
    }
}
