package com.ugport.web

import android.webkit.JavascriptInterface
import java.util.concurrent.atomic.AtomicLong

/** 页面 → 原生的单向状态上报，不接受任何控制指令。 */
class WebMediaBridge(private val onState: (String) -> Unit) {

    private val lastAcceptedAt = AtomicLong(0L)

    @JavascriptInterface
    fun postState(json: String?) {
        if (json.isNullOrBlank() || json.length > 4096) return
        val now = System.currentTimeMillis()
        val previous = lastAcceptedAt.get()
        if (now - previous < 100) return
        if (!lastAcceptedAt.compareAndSet(previous, now)) return
        onState(json)
    }
}
