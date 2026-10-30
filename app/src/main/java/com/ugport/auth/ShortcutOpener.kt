package com.ugport.auth

import com.ugport.data.model.Shortcut
import com.ugport.data.repo.ProfileRepository
import com.ugport.network.Diagnostics
import com.ugport.network.UgreenApi
import com.ugport.util.UrlTools

sealed interface OpenResult {
    data class Ready(val url: String) : OpenResult
    data class NeedOtp(val nasId: String, val tokenId: String?, val otpType: Int?) : OpenResult
    data class LoginFallback(val nasId: String, val url: String, val message: String) : OpenResult
    data class Error(val message: String) : OpenResult
}

/**
 * 把端口配置解析成 WebView 可以直接加载的地址。
 *
 * 两条路径：
 * - Docker 容器端口：`GET dockerToken` 拿到一次性 redirect_url。必须先用原生请求访问它，
 *   取出 `ugreen-proxy-token` 写入 CookieManager，再加载
 *   `app-<port>-<alias>.<dockerDomain>`。redirect_url 只能使用一次，而且会 302 到 `/`，
 *   所以不能把它交给 WebView 预加载，否则会与原生 Cookie 交换竞争。
 * - 网关端口（browserUrl）：`GET GetUid` 取 uid，拼到目标地址上。
 *
 * token 失效时自动重新授权一次。重授权只清内存缓存，持久化 token 保留到新 token 成功后再覆盖，
 * 避免一次网络失败就把长期 token 丢掉。
 */
class ShortcutOpener(
    private val repo: ProfileRepository,
    private val sessions: SessionManager,
    private val uids: UidProvider,
    private val apiFactory: (String) -> UgreenApi,
    private val endpointResolver: EndpointResolver,
    private val diagnostics: Diagnostics,
) {
    suspend fun open(shortcut: Shortcut): OpenResult {
        val nas = repo.nas(shortcut.nasId)
            ?: return OpenResult.Error("快捷方式所属 NAS 不存在")

        var token = when (val session = sessions.ensureSession(nas.id)) {
            is SessionResult.OtpRequired ->
                return OpenResult.NeedOtp(nas.id, session.tokenId, session.otpType)
            is SessionResult.Failure -> {
                diagnostics.log("open: session failed: ${session.message}")
                return if (nas.autoLogin) {
                    OpenResult.Error(session.message)
                } else {
                    val connectionId = UrlTools.normalizeConnectionId(
                        nas.connectionId.ifBlank { UrlTools.parseConnectionId(nas.baseUrl) }
                    )
                    val relay = nas.relayDomain ?: UrlTools.parseRelayDomain(nas.baseUrl)
                    OpenResult.LoginFallback(
                        nas.id,
                        "${UrlTools.buildLoginBaseUrl(connectionId, relay)}/desktop/#/login/account",
                        session.message,
                    )
                }
            }
            is SessionResult.Success -> session.token
        }

        var endpoints = endpointResolver.resolve(nas.id)
            ?: return OpenResult.Error("无法解析服务器标识，请检查连接标识和网络")

        val port = shortcut.port.takeIf { it > 0 } ?: UrlTools.extractAppPort(shortcut.url)
        val path = UrlTools.normalizePath(
            if (shortcut.path.isNotBlank()) shortcut.path else UrlTools.parsePath(shortcut.url)
        )
        val targetUrl = if (port != null && port > 0) {
            UrlTools.buildPortUrl(endpoints.connectionId, endpoints.dockerDomain, port, path)
        } else {
            shortcut.url
        }
        if (targetUrl.isBlank()) return OpenResult.Error("端口号无效")

        var finalUrl = targetUrl
        if (shortcut.appendUid) {
            if (port != null && port > 0) {
                // Docker 容器端口：必须用 dockerToken 换取一次性 redirect_url
                var redirect = apiFactory(endpoints.loginBaseUrl).getDockerRedirect(port, token)
                if (redirect.url.isNullOrBlank()) {
                    if (isAuthFailure(redirect.code)) {
                        // token 失效（keepalive 到期/被服务端注销）：自动重新授权一次
                        diagnostics.log("open: token expired (${redirect.code}), re-authorize")
                        // 只清内存缓存；持久化的 token 保留到重新授权成功后再覆盖，
                        // 避免一次网络失败就把长期 token 丢掉。
                        sessions.clearCachedToken(nas.id)
                        when (val reauth = sessions.ensureSession(nas.id, force = true)) {
                            is SessionResult.OtpRequired ->
                                return OpenResult.NeedOtp(nas.id, reauth.tokenId, reauth.otpType)
                            is SessionResult.Success -> {
                                token = reauth.token
                                endpoints = endpointResolver.cached(nas.id) ?: endpoints
                                redirect = apiFactory(endpoints.loginBaseUrl)
                                    .getDockerRedirect(port, token)
                                finalUrl = UrlTools.buildPortUrl(
                                    endpoints.connectionId,
                                    endpoints.dockerDomain,
                                    port,
                                    path,
                                )
                            }
                            is SessionResult.Failure ->
                                diagnostics.log("open: re-authorize failed: ${reauth.message}")
                        }
                    } else {
                        diagnostics.log("open: dockerToken failed (${redirect.code} ${redirect.message}), re-resolve")
                        val fresh = endpointResolver.resolve(nas.id, force = true)
                        if (fresh != null && fresh.loginBaseUrl != endpoints.loginBaseUrl) {
                            endpoints = fresh
                            redirect = apiFactory(fresh.loginBaseUrl).getDockerRedirect(port, token)
                            finalUrl = UrlTools.buildPortUrl(
                                fresh.connectionId,
                                fresh.dockerDomain,
                                port,
                                path,
                            )
                        }
                    }
                }
                if (!redirect.url.isNullOrBlank()) {
                    finalUrl = redirect.url
                }
            } else {
                val uid = uids.uid(nas.id, token)
                if (!uid.isNullOrBlank()) {
                    finalUrl = UrlTools.appendUid(targetUrl, uid)
                } else {
                    diagnostics.log("open: uid unavailable, try loading url directly")
                }
            }
        }

        diagnostics.log("open: ready (port=${port ?: 0})")
        diagnostics.detail("open: ${shortcut.name} -> ${redact(finalUrl)}")
        return OpenResult.Ready(finalUrl)
    }

    private fun isAuthFailure(code: Int): Boolean = code == 1024 || code == 1010 || code == 401

    private fun redact(url: String): String =
        Regex("([?&](?:uid|token)=)[^&]+").replace(url) { match -> match.groupValues[1] + "***" }
}
