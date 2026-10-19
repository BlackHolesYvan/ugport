package com.ugport.web

import android.webkit.JavascriptInterface

class WebBridge(private val onToken: (String) -> Unit) {

    @JavascriptInterface
    fun onToken(token: String) {
        onToken(token)
    }
}
