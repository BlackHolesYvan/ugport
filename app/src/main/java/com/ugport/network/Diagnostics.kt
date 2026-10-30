package com.ugport.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内存诊断日志，最多保留 [MAX_LINES] 行，只在进程内，不落盘。
 *
 * 分层约定：
 * - [event]：关键节点，会出现在设置页并能被用户复制。只写可公开分享的信息
 *   （状态、错误码、端口、NAS id 前 8 位），不要写账号与完整 URL。
 * - [detail]：高频细节，只写 logcat。可以包含 host、URL，但不得包含密码与明文 token。
 */
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

    /**
     * 脱敏后的 URL 可以展示或复制：
     * - userinfo 部分（`https://user:pass@host`）替换为 `***@`
     * - query 中的 token/uid/ugk/code/key/sign 替换为 `***`
     *
     * host、路径与端口仍会保留，它们可能暴露 NAS 域名，因此完整 URL 只应写进 [detail]。
     */
    fun redactUrl(url: String): String {
        val withoutUserInfo = USER_INFO.replace(url) { it.groupValues[1] + "***@" }
        return SENSITIVE_QUERY.replace(withoutUserInfo) { it.groupValues[1] + "***" }
    }

    fun maskToken(token: String?): String {
        if (token.isNullOrEmpty()) return "<none>"
        if (token.length <= 12) return "***"
        return token.take(6) + "..." + token.takeLast(4)
    }

    private companion object {
        const val MAX_LINES = 200
        const val DEFAULT_TAG = "UgPort"
        val USER_INFO = Regex("(://)[^/@\\s]+@")
        val SENSITIVE_QUERY = Regex("([?&](?:token|uid|ugk|code|key|sign)=)[^&\"'\\s]+")
    }
}
