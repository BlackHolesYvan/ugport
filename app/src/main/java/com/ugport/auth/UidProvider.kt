package com.ugport.auth

import com.ugport.data.repo.ProfileRepository
import com.ugport.network.Diagnostics
import com.ugport.network.UgreenApi
import java.util.concurrent.ConcurrentHashMap

class UidProvider(
    private val repo: ProfileRepository,
    private val sessions: SessionManager,
    private val apiFactory: (String) -> UgreenApi,
    private val endpointResolver: EndpointResolver,
    private val diagnostics: Diagnostics,
) {
    private data class Entry(val uid: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun invalidate(nasId: String) {
        cache.remove(nasId)
    }

    suspend fun uid(nasId: String, tokenOverride: String? = null): String? {
        val nas = repo.nas(nasId) ?: return null
        cache[nasId]?.let {
            if (System.currentTimeMillis() - it.at < TTL_MS) return it.uid
        }

        var token = tokenOverride ?: repo.tokenOf(nas)
        if (token.isNullOrBlank()) {
            when (val session = sessions.ensureSession(nasId)) {
                is SessionResult.Success -> token = session.token
                else -> return null
            }
        }

        val endpoints = endpointResolver.resolve(nasId) ?: return null
        val api = apiFactory(endpoints.loginBaseUrl)
        val first = api.getUid(token, nas.authType)
        if (!first.uid.isNullOrBlank()) {
            cache[nasId] = Entry(first.uid, System.currentTimeMillis())
            return first.uid
        }

        // token 可能已失效，强制重登后再试一次
        diagnostics.log("uid: first attempt failed (${first.code} ${first.message}), re-login")
        sessions.clearCachedToken(nasId)
        val session = sessions.ensureSession(nasId, force = true)
        if (session !is SessionResult.Success) return null
        val second = api.getUid(session.token, nas.authType)
        if (!second.uid.isNullOrBlank()) {
            cache[nasId] = Entry(second.uid, System.currentTimeMillis())
            return second.uid
        }
        diagnostics.log("uid: second attempt failed (${second.code} ${second.message})")
        return null
    }

    private companion object {
        const val TTL_MS = 30 * 60 * 1000L
    }
}
