package com.ugport.ui.web

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import com.ugport.network.HttpFactory

/**
 * 端口页与 Android 平台交互的辅助函数：Cookie 注入、下载、外部浏览器、Activity 查找。
 * 这些函数不持有 Compose 状态，便于单独测试与复用。
 */

internal fun seedCookies(cookieJar: HttpFactory.MemoryCookieJar, url: String) {
    val manager = CookieManager.getInstance()
    cookieJar.all().forEach { cookie ->
        val host = cookie.domain.removePrefix(".")
        val value = "${cookie.name}=${cookie.value}; path=${cookie.path}; domain=${cookie.domain}"
        runCatching { manager.setCookie("https://$host", value) }
    }
    manager.flush()
}

internal fun enqueueDownload(
    context: Context,
    url: String,
    contentDisposition: String?,
    mimeType: String?,
    userAgent: String,
) {
    runCatching {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(url, contentDisposition, mimeType),
            )
        }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }
}


internal fun openExternal(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** LocalActivity 为空时（测试或特殊宿主）沿 ContextWrapper 链找 Activity。 */
internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
