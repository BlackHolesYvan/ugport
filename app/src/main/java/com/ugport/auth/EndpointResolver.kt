package com.ugport.auth

import com.ugport.data.repo.ProfileRepository
import com.ugport.network.Diagnostics
import com.ugport.util.UrlTools
import java.util.concurrent.ConcurrentHashMap

data class NasEndpoints(
    val connectionId: String,
    val relayDomain: String,
    val dockerDomain: String,
    val loginBaseUrl: String,
)

/**
 * 把「连接标识」解析成当前服务器域名。
 *
 * 双层缓存：
 * - 内存 [cache]：TTL [TTL_MS]（30 分钟），避免频繁请求 nodeInfo。
 * - 落盘的 relayDomain/dockerDomain：与 `resolvedAt` 一起判断，同样 30 分钟。
 *
 * 服务器标识可能变化，所以登录失败或 dockerToken 失败时会 `force = true` 重新解析；
 * 解析失败则回退到上次成功保存的域名，保证离线/接口抖动时仍能打开已配置的端口。
 */
class EndpointResolver(
    private val repo: ProfileRepository,
    private val resolver: ServerResolver,
    private val diagnostics: Diagnostics,
) {
    private data class Cached(val endpoints: NasEndpoints, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun cached(nasId: String): NasEndpoints? = cache[nasId]?.endpoints

    fun invalidate(nasId: String) {
        cache.remove(nasId)
    }

    suspend fun resolve(nasId: String, force: Boolean = false): NasEndpoints? {
        val nas = repo.nas(nasId) ?: return null
        val connectionId = UrlTools.normalizeConnectionId(
            nas.connectionId.ifBlank { UrlTools.parseConnectionId(nas.baseUrl) }
        )
        if (connectionId.isBlank()) return null

        val now = System.currentTimeMillis()
        cache[nasId]?.let {
            if (!force && now - it.at < TTL_MS) return it.endpoints
        }

        val storedRelay = nas.relayDomain ?: UrlTools.parseRelayDomain(nas.baseUrl)
        val storedDocker = nas.dockerDomain
        if (!force &&
            !storedRelay.isNullOrBlank() &&
            !storedDocker.isNullOrBlank() &&
            now - nas.resolvedAt < TTL_MS
        ) {
            return NasEndpoints(
                connectionId = connectionId,
                relayDomain = storedRelay,
                dockerDomain = storedDocker,
                loginBaseUrl = UrlTools.buildLoginBaseUrl(connectionId, storedRelay),
            ).also { cache[nasId] = Cached(it, now) }
        }

        val resolved = resolver.resolve(connectionId)
        if (resolved.code == 200 &&
            !resolved.relayDomain.isNullOrBlank() &&
            !resolved.dockerDomain.isNullOrBlank()
        ) {
            repo.updateResolution(nasId, resolved.relayDomain, resolved.dockerDomain)
            val endpoints = NasEndpoints(
                connectionId = connectionId,
                relayDomain = resolved.relayDomain,
                dockerDomain = resolved.dockerDomain,
                loginBaseUrl = UrlTools.buildLoginBaseUrl(connectionId, resolved.relayDomain),
            )
            cache[nasId] = Cached(endpoints, now)
            return endpoints
        }

        // 解析失败时优先沿用上次成功的服务器标识
        if (!storedRelay.isNullOrBlank() && !storedDocker.isNullOrBlank()) {
            diagnostics.log("resolve: fallback to stored domains (nas=${nasId.take(8)})")
            diagnostics.detail("resolve: fallback connection=$connectionId")
            return NasEndpoints(
                connectionId = connectionId,
                relayDomain = storedRelay,
                dockerDomain = storedDocker,
                loginBaseUrl = UrlTools.buildLoginBaseUrl(connectionId, storedRelay),
            ).also { cache[nasId] = Cached(it, now) }
        }

        diagnostics.log("resolve: failed (nas=${nasId.take(8)}, code=${resolved.code})")
        diagnostics.detail("resolve: failed connection=$connectionId message=${resolved.message}")
        return null
    }

    private companion object {
        const val TTL_MS = 30 * 60 * 1000L
    }
}
