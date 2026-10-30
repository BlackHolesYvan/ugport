package com.ugport.auth

import com.ugport.data.model.NasProfile
import com.ugport.data.repo.ProfileRepository
import com.ugport.network.Diagnostics
import com.ugport.network.UgreenApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

sealed interface SessionResult {
    data class Success(val token: String, val authType: String?) : SessionResult
    data class OtpRequired(val tokenId: String?, val otpType: Int?) : SessionResult
    data class Failure(val message: String) : SessionResult
}

/**
 * 单台 NAS 的登录会话管理。
 *
 * token 语义：登录返回的 keepalive token 可以长期复用，
 * 因此只要有内存或本地缓存就直接使用，
 * 不再每次打开端口都调用 is_login；只有接口返回 1024/1010/401，
 * 或页面落到登录页时才强制重新授权。
 *
 * 并发：每台 NAS 一把 [Mutex]，避免同时打开多个端口时重复登录。
 * 失败策略：连续失败 [MAX_FAILURES] 次后冷却 [COOLDOWN_MS]，避免把账号刷进风控。
 */
class SessionManager(
    private val repo: ProfileRepository,
    private val apiFactory: (String) -> UgreenApi,
    private val endpointResolver: EndpointResolver,
    private val diagnostics: Diagnostics,
) {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val failureCount = ConcurrentHashMap<String, Int>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    private data class CachedToken(val token: String, val at: Long)

    suspend fun ensureSession(nasId: String, force: Boolean = false): SessionResult {
        val mutex = mutexes.computeIfAbsent(nasId) { Mutex() }
        return mutex.withLock {
            val nas = repo.nas(nasId) ?: return@withLock SessionResult.Failure("NAS 配置不存在")

            // keepalive 登录返回的 token 可以长期使用：有 token 就直接用，
            // 不再每次打开端口都调用 is_login 校验；
    // 只有接口返回 1024 或页面跳到登录页时才重新授权。
            if (!force) {
                tokenCache[nasId]?.let {
                    diagnostics.log("session: use memory token (nas=${nasId.take(8)})")
                    return@withLock SessionResult.Success(it.token, nas.authType)
                }
                val stored = repo.tokenOf(nas)
                if (!stored.isNullOrBlank()) {
                    tokenCache[nasId] = CachedToken(stored, System.currentTimeMillis())
                    diagnostics.log("session: use stored keepalive token (nas=${nasId.take(8)})")
                    return@withLock SessionResult.Success(stored, nas.authType)
                }
            }

            val endpoints = endpointResolver.resolve(nasId)
                ?: return@withLock SessionResult.Failure("无法解析服务器标识，请检查连接标识和网络")
            var api = apiFactory(endpoints.loginBaseUrl)

            if (!nas.autoLogin) {
                return@withLock SessionResult.Failure("未开启自动登录")
            }
            if ((cooldownUntil[nasId] ?: 0L) > System.currentTimeMillis()) {
                return@withLock SessionResult.Failure("登录失败次数过多，请 5 分钟后再试")
            }

            val password = repo.passwordOf(nas)
            if (password.isNullOrEmpty()) {
                return@withLock SessionResult.Failure("未保存密码，请编辑 NAS 配置")
            }

            diagnostics.log("session: login start (nas=${nasId.take(8)})")
            diagnostics.detail("session: login host=${endpoints.loginBaseUrl} user=${nas.username}")
            var result = api.login(nas.username, password)

            // 服务器标识可能已变化：登录失败时强制重新解析一次
            if (result.token.isNullOrBlank() && !result.enableOtp) {
                val fresh = endpointResolver.resolve(nasId, force = true)
                if (fresh != null && fresh.loginBaseUrl != endpoints.loginBaseUrl) {
                    diagnostics.log("session: server changed, retry once")
                    diagnostics.detail("session: retry host=${fresh.loginBaseUrl}")
                    api = apiFactory(fresh.loginBaseUrl)
                    result = api.login(nas.username, password)
                }
            }

            if (!result.token.isNullOrBlank()) {
                repo.saveToken(nasId, result.token, result.authType)
                tokenCache[nasId] = CachedToken(result.token!!, System.currentTimeMillis())
                failureCount.remove(nasId)
                cooldownUntil.remove(nasId)
                diagnostics.log("session: login ok (nas=${nasId.take(8)})")
                return@withLock SessionResult.Success(result.token, result.authType)
            }
            if (result.enableOtp) {
                diagnostics.log("session: otp required (nas=${nasId.take(8)})")
                return@withLock SessionResult.OtpRequired(result.tokenId, result.otpType)
            }

            val count = (failureCount[nasId] ?: 0) + 1
            failureCount[nasId] = count
            if (count >= MAX_FAILURES) {
                cooldownUntil[nasId] = System.currentTimeMillis() + COOLDOWN_MS
            }
            SessionResult.Failure(result.message.ifBlank { "登录失败（code=${result.code}）" })
        }
    }

    suspend fun submitOtp(nasId: String, code: String, tokenId: String?, otpType: Int?): SessionResult {
        val mutex = mutexes.computeIfAbsent(nasId) { Mutex() }
        return mutex.withLock {
            val nas = repo.nas(nasId) ?: return@withLock SessionResult.Failure("NAS 配置不存在")
            val endpoints = endpointResolver.resolve(nasId)
                ?: return@withLock SessionResult.Failure("无法解析服务器标识")
            val api = apiFactory(endpoints.loginBaseUrl)
            val result = api.codeLogin(code, otpType, tokenId)
            if (!result.token.isNullOrBlank()) {
                repo.saveToken(nasId, result.token, result.authType)
                tokenCache[nasId] = CachedToken(result.token, System.currentTimeMillis())
                failureCount.remove(nasId)
                cooldownUntil.remove(nasId)
                SessionResult.Success(result.token, result.authType)
            } else {
                SessionResult.Failure(result.message.ifBlank { "验证码错误" })
            }
        }
    }

    suspend fun acceptExternalToken(nasId: String, token: String): SessionResult {
        val nas = repo.nas(nasId) ?: return SessionResult.Failure("NAS 配置不存在")
        repo.saveToken(nasId, token, nas.authType)
        tokenCache[nasId] = CachedToken(token, System.currentTimeMillis())
        failureCount.remove(nasId)
        cooldownUntil.remove(nasId)
        diagnostics.log("session: accepted token from webview (nas=${nasId.take(8)})")
        return SessionResult.Success(token, nas.authType)
    }

    suspend fun currentToken(nas: NasProfile): String? = repo.tokenOf(nas)

    fun clearCachedToken(nasId: String) {
        tokenCache.remove(nasId)
        endpointResolver.invalidate(nasId)
    }

    suspend fun endpointsFor(nasId: String, force: Boolean = false): NasEndpoints? =
        endpointResolver.resolve(nasId, force)

    /** 修改配置或手动重试时清除失败冷却，避免“登录失败次数过多”卡住。 */
    fun reset(nasId: String) {
        failureCount.remove(nasId)
        cooldownUntil.remove(nasId)
        tokenCache.remove(nasId)
    }

    private companion object {
        const val COOLDOWN_MS = 5 * 60 * 1000L
        const val MAX_FAILURES = 3
    }
}
