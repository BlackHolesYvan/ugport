package com.ugport.auth

import com.ugport.network.Diagnostics
import com.ugport.network.UgreenApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Docker 网关的 redirect_url 是一个 HTML 页面，页面内写入 ugreen-proxy-token Cookie。
 * 直接在 WebView 里依赖页面 JS 写 Cookie 在部分旧 WebView 上会被 SameSite=None 拦掉，
 * 这里用原生请求取出 Cookie 值，再交给 CookieManager，随后加载应用根路径。
 */
class DockerAuthExchange(
    private val client: OkHttpClient,
    private val diagnostics: Diagnostics,
) {
    /**
     * 请求一次性的 redirect_url，返回 `ugreen-proxy-token` 的值。
     *
     * 失败返回 null；redirect_url 只能使用一次，调用方不应重试同一个地址。
     */
    suspend fun exchange(redirectUrl: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(redirectUrl)
            .get()
            .header("User-Agent", UgreenApi.DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                val cookie = COOKIE_PATTERN.find(html)?.groupValues?.getOrNull(1)
                diagnostics.log(
                    "dockerAuth http=${response.code} " +
                        "cookie=${if (cookie.isNullOrBlank()) "none" else "present"}"
                )
                cookie
            }
        }.getOrElse {
            diagnostics.log("dockerAuth failed: ${it.message}")
            null
        }
    }

    private companion object {
        val COOKIE_PATTERN = Regex("ugreen-proxy-token=([^;'\\s]+)")
    }
}
