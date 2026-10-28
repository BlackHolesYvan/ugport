package com.ugport.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Diagnostics {

    private val lock = Any()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        synchronized(lock) {
            val next = (_lines.value + "[$time] $message").takeLast(MAX_LINES)
            _lines.value = next
        }
    }

    /** 关键事件：进入诊断页，同时写 logcat。 */
    fun event(message: String, tag: String = DEFAULT_TAG) {
        log(message)
        Log.i(tag, message)
    }

    /** 高频细节：只写 logcat，不占诊断缓冲。 */
    fun detail(message: String, tag: String = DEFAULT_TAG) {
        Log.d(tag, message)
    }

    fun clear() {
        synchronized(lock) { _lines.value = emptyList() }
    }

    fun redactUrl(url: String): String =
        Regex("([?&](?:token|uid|ugk|code|key|sign)=)[^&\"'\\s]+").replace(url) {
            it.groupValues[1] + "***"
        }

    fun maskToken(token: String?): String {
        if (token.isNullOrEmpty()) return "<none>"
        if (token.length <= 12) return "***"
        return token.take(6) + "..." + token.takeLast(4)
    }

    private companion object {
        const val MAX_LINES = 200
        const val DEFAULT_TAG = "UgPort"
    }
}
