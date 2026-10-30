package com.ugport.util

import android.net.Uri
import com.ugport.data.model.NasProfile
import java.net.URI

object UrlTools {

    /** 只保留连接标识，例如 https://my-nas.xx.ug.link/ -> my-nas */
    fun normalizeConnectionId(input: String): String {
        var value = input.trim().lowercase()
        value = value.removePrefix("https://").removePrefix("http://")
        value = value.substringBefore('/').substringBefore('?').substringBefore('#')
        return value.substringBefore('.').trim()
    }

    fun parseConnectionId(baseUrl: String): String {
        if (baseUrl.isBlank()) return ""
        val host = runCatching { Uri.parse(normalizeBaseUrl(baseUrl)).host }.getOrNull() ?: return ""
        return host.substringBefore('.')
    }

    /** alias.xx.ug.link -> xx.ug.link */
    fun parseRelayDomain(baseUrl: String): String? {
        if (baseUrl.isBlank()) return null
        val host = runCatching { Uri.parse(normalizeBaseUrl(baseUrl)).host }.getOrNull() ?: return null
        val parts = host.split('.')
        if (parts.size >= 3 && parts.takeLast(2) == listOf("ug", "link")) {
            return parts.drop(1).joinToString(".")
        }
        return null
    }

    fun buildLoginBaseUrl(connectionId: String, relayDomain: String?): String =
        if (!relayDomain.isNullOrBlank()) "https://$connectionId.$relayDomain"
        else "https://$connectionId.ug.link"

    fun normalizePath(path: String): String {
        val value = path.trim()
        if (value.isEmpty() || value == "/") return ""
        return if (value.startsWith("/")) value else "/$value"
    }

    fun parsePath(url: String): String {
        if (url.isBlank()) return ""
        val path = runCatching { Uri.parse(normalizeTargetUrl(url)).path }.getOrNull() ?: return ""
        return normalizePath(path)
    }

    fun buildPortUrl(connectionId: String, dockerDomain: String, port: Int, path: String): String =
        "https://app-$port-$connectionId.$dockerDomain${normalizePath(path)}"

    fun normalizeBaseUrl(input: String): String {
        var value = input.trim()
        if (value.isEmpty()) return value
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://$value"
        }
        return value.trimEnd('/')
    }

    fun normalizeTargetUrl(input: String): String {
        var value = input.trim()
        if (value.isEmpty()) return value
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://$value"
        }
        return value
    }

    fun appendUid(url: String, uid: String): String {
        if (uid.isBlank()) return url
        return runCatching {
            val uri = Uri.parse(url)
            if (uri.getQueryParameter("uid") != null) return url
            uri.buildUpon().appendQueryParameter("uid", uid).build().toString()
        }.getOrDefault(url)
    }

    /** 从 app-8080-xxxx.ugdocker.link 这类地址中取出 8080。 */
    fun extractAppPort(url: String): Int? {
        val uri = runCatching { Uri.parse(normalizeTargetUrl(url)) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (host.startsWith("app-")) {
            return host.removePrefix("app-").substringBefore('-').toIntOrNull()
        }
        return uri.port.takeIf { it > 0 }
    }

    fun isDockerGatewayUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(normalizeTargetUrl(url)).host }.getOrNull() ?: return false
        return host.startsWith("app-") && host.endsWith(".ugdocker.link")
    }

    /** NAS 的 UGOS 登录域名，例如 my-nas.xx.ug.link。 */
    fun loginHost(nas: NasProfile): String? {
        val connectionId = normalizeConnectionId(
            nas.connectionId.ifBlank { parseConnectionId(nas.baseUrl) }
        )
        if (connectionId.isBlank()) return null
        val relay = nas.relayDomain ?: parseRelayDomain(nas.baseUrl)
        if (relay.isNullOrBlank()) return null
        return "$connectionId.$relay"
    }

    /**
     * 只把 UGOS 自己的登录页当作登录页。
     * 容器端口（*.ugdocker.link）与第三方应用的 /login 路由一律排除，
     * 否则会误触发 NAS 重新登录（开启 OTP 的账号会被弹二次验证）。
     */
    fun isUgreenLoginUrl(url: String?, loginHost: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(normalizeTargetUrl(url)) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host.endsWith(".ugdocker.link")) return false
        if (!loginHost.isNullOrBlank()) {
            if (!host.equals(loginHost.lowercase())) return false
        } else if (!host.endsWith(".ug.link")) {
            return false
        }
        val path = uri.path?.lowercase().orEmpty()
        val fragment = uri.fragment?.lowercase().orEmpty()
        if (path.startsWith("/ugreen")) return true
        if (path.startsWith("/desktop") && fragment.contains("login")) return true
        return fragment.contains("/login")
    }

    fun defaultAppendUid(url: String): Boolean {
        val host = runCatching { Uri.parse(normalizeTargetUrl(url)).host }.getOrNull() ?: return false
        return host.endsWith(".ugdocker.link") || host.endsWith(".ug.link")
    }
}
