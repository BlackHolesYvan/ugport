package com.ugport.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class PlaybackPhase {
    NONE,
    PLAYING,
    BUFFERING,
    PAUSED,
    ENDED,
    ERROR,
}

data class PlaybackSnapshot(
    val phase: PlaybackPhase = PlaybackPhase.NONE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val kind: String = "audio",
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
) {
    val playing: Boolean
        get() = phase == PlaybackPhase.PLAYING || phase == PlaybackPhase.BUFFERING

    val hasMedia: Boolean
        get() = phase != PlaybackPhase.NONE
}

@Serializable
internal data class MediaPayload(
    val v: Int = 1,
    val has: Boolean = true,
    val playing: Boolean = false,
    val state: String = "none",
    val pos: Long = 0L,
    val dur: Long = 0L,
    val rate: Float = 1f,
    val kind: String = "audio",
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
)

/** 解析页面注入脚本上报的播放状态，任何异常输入都返回 null。 */
object MediaStateParser {

    private const val MAX_RAW_LENGTH = 4096
    private const val MAX_TEXT_LENGTH = 160
    private const val MAX_POSITION_MS = 86_400_000L
    private const val MAX_DURATION_MS = 7 * 86_400_000L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(raw: String?): PlaybackSnapshot? {
        if (raw.isNullOrBlank() || raw.length > MAX_RAW_LENGTH) return null
        val payload = runCatching { json.decodeFromString<MediaPayload>(raw) }.getOrNull() ?: return null
        return toSnapshot(payload)
    }

    private fun toSnapshot(payload: MediaPayload): PlaybackSnapshot {
        val duration = payload.dur.coerceIn(0L, MAX_DURATION_MS)
        var position = payload.pos.coerceIn(0L, MAX_POSITION_MS)
        if (duration > 0) position = position.coerceAtMost(duration)

        val phase = when {
            payload.playing && payload.state == "buffering" -> PlaybackPhase.BUFFERING
            payload.playing -> PlaybackPhase.PLAYING
            payload.state == "ended" -> PlaybackPhase.ENDED
            payload.state == "error" -> PlaybackPhase.ERROR
            payload.state == "paused" || payload.state == "buffering" -> PlaybackPhase.PAUSED
            else -> PlaybackPhase.NONE
        }

        return PlaybackSnapshot(
            phase = phase,
            positionMs = position,
            durationMs = duration,
            title = text(payload.title),
            artist = text(payload.artist),
            album = text(payload.album),
            kind = text(payload.kind, 16).ifBlank { "audio" },
            canPrev = payload.canPrev,
            canNext = payload.canNext,
        )
    }

    private fun text(value: String?, max: Int = MAX_TEXT_LENGTH): String {
        if (value.isNullOrEmpty()) return ""
        val cleaned = buildString(value.length) {
            for (ch in value) {
                val code = ch.code
                append(if (code >= 0x20 && code != 0x7f) ch else ' ')
            }
        }.trim()
        return if (cleaned.length > max) cleaned.take(max) else cleaned
    }
}
