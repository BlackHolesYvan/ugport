package com.ugport.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ugport.auth.OpenResult
import com.ugport.auth.SessionResult
import com.ugport.data.model.AppData
import com.ugport.data.model.NasProfile
import com.ugport.data.model.Shortcut
import com.ugport.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class WebTarget(
    val url: String,
    val title: String,
    val nasId: String,
    val shortcut: Shortcut,
)

data class OtpPrompt(
    val nasId: String,
    val tokenId: String?,
    val otpType: Int?,
    /** 从快捷方式打开时带上；从「测试登录」触发时为 null。 */
    val shortcut: Shortcut? = null,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {

    val repo = container.repo
    val diagnostics = container.diagnostics
    val data: StateFlow<AppData> = repo.state

    var webTarget by mutableStateOf<WebTarget?>(null)
        private set

    var otpPrompt by mutableStateOf<OtpPrompt?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)

    private val webTokenLock = AtomicBoolean(false)
    private val nativeReauthLock = AtomicBoolean(false)
    private var reauthAttempts = 0
    private val preparedRedirects = ConcurrentHashMap<String, String>()
    private val prepareMutex = Mutex()

    fun openShortcut(shortcut: Shortcut) {
        if (repo.nas(shortcut.nasId) == null) {
            message = "快捷方式所属 NAS 不存在，请重新添加或编辑"
            return
        }
        reauthAttempts = 0
        preparedRedirects.clear()
        launchOpen(shortcut)
    }

    /**
     * WebView 落到登录页时的兜底：用保存的账号密码重新授权一次。
     * 正常情况下 keepalive token 会直接可用，不会走到这里。
     */
    fun onLoginPageDetected(target: WebTarget) {
        if (reauthAttempts >= 1) return
        if (!nativeReauthLock.compareAndSet(false, true)) return
        reauthAttempts++
        diagnostics.log("webview: login page detected, re-authorize (${target.title})")
        viewModelScope.launch {
            try {
                when (val session = container.sessions.ensureSession(target.nasId, force = true)) {
                    is SessionResult.Success -> {
                        container.uids.invalidate(target.nasId)
                        launchOpen(target.shortcut)
                    }
                    is SessionResult.OtpRequired ->
                        otpPrompt = OtpPrompt(target.nasId, session.tokenId, session.otpType, target.shortcut)
                    is SessionResult.Failure ->
                        message = session.message
                }
            } finally {
                nativeReauthLock.set(false)
            }
        }
    }

    private fun launchOpen(shortcut: Shortcut) {
        busy = true
        viewModelScope.launch {
            try {
                when (val result = container.opener.open(shortcut)) {
                    is OpenResult.Ready ->
                        webTarget = WebTarget(result.url, shortcut.name, shortcut.nasId, shortcut)
                    is OpenResult.NeedOtp ->
                        otpPrompt = OtpPrompt(result.nasId, result.tokenId, result.otpType, shortcut)
                    is OpenResult.LoginFallback -> {
                        message = result.message
                        webTarget = WebTarget(result.url, shortcut.name, result.nasId, shortcut)
                    }
                    is OpenResult.Error -> message = result.message
                }
            } finally {
                busy = false
            }
        }
    }

    fun submitOtp(code: String) {
        val prompt = otpPrompt ?: return
        val shortcut = prompt.shortcut
        otpPrompt = null
        busy = true
        viewModelScope.launch {
            try {
                when (val result = container.sessions.submitOtp(prompt.nasId, code, prompt.tokenId, prompt.otpType)) {
                    is SessionResult.Success -> {
                        container.uids.invalidate(prompt.nasId)
                        if (shortcut != null) {
                            if (repo.nas(shortcut.nasId) == null) {
                                message = "该快捷方式所属 NAS 已被删除，请重新添加"
                            } else {
                                launchOpen(shortcut)
                            }
                        } else {
                            val endpoints = container.endpoints.resolve(prompt.nasId)
                            message = if (endpoints != null) {
                                "验证成功，服务器：${endpoints.relayDomain}"
                            } else {
                                "验证成功"
                            }
                        }
                    }
                    is SessionResult.OtpRequired ->
                        otpPrompt = OtpPrompt(prompt.nasId, result.tokenId, result.otpType, shortcut)
                    is SessionResult.Failure -> message = result.message
                }
            } finally {
                busy = false
            }
        }
    }

    fun dismissOtp() {
        otpPrompt = null
    }

    /** WebView 登录成功后从 localStorage 取到 token，交给原生继续取 uid。 */
    fun onWebToken(nasId: String, token: String, shortcut: Shortcut) {
        if (token.isBlank()) return
        if (!webTokenLock.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val nas = repo.nas(nasId) ?: return@launch
                if (repo.tokenOf(nas) == token) return@launch
                val result = container.sessions.acceptExternalToken(nasId, token)
                if (result is SessionResult.Success) {
                    container.uids.invalidate(nasId)
                    launchOpen(shortcut)
                }
            } finally {
                webTokenLock.set(false)
            }
        }
    }

    fun closeWeb() {
        webTarget = null
        preparedRedirects.clear()
    }

    /**
     * Docker 网关的 redirect_url 需要先换取 Cookie，再加载应用根路径。
     * 失败时返回原 URL，让 WebView 里的页面 JS 兜底。
     */
    suspend fun prepareDockerRedirect(target: WebTarget): String {
        val url = target.url
        if (!url.contains("/api/ugreen/auth")) return url
        preparedRedirects[url]?.let { return it }
        // 同一个一次性鉴权 URL 只交换一次，避免 WebView 重组时并发请求导致 400
        return prepareMutex.withLock {
            preparedRedirects[url]?.let { return@withLock it }
            val result = doPrepareDockerRedirect(target)
            preparedRedirects[url] = result
            result
        }
    }

    private suspend fun doPrepareDockerRedirect(target: WebTarget): String {
        val url = target.url
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return url
        val host = uri.host ?: return url
        val scheme = uri.scheme ?: "https"
        val path = com.ugport.util.UrlTools.normalizePath(
            if (target.shortcut.path.isNotBlank()) target.shortcut.path
            else com.ugport.util.UrlTools.parsePath(target.shortcut.url)
        )
        var cookie: String? = null
        for (attempt in 1..3) {
            cookie = container.dockerAuth.exchange(url)
            if (cookie != null) break
            if (attempt < 3) {
                diagnostics.log("dockerAuth: retry ${attempt}/3")
                delay(500)
            }
        }
        if (cookie != null) {
            val manager = android.webkit.CookieManager.getInstance()
            manager.setCookie("$scheme://$host", "ugreen-proxy-token=$cookie; Path=/; Secure")
            manager.flush()
        } else {
            // 鉴权页取 Cookie 失败时仍然加载目标路径，避免回落到根路径
            diagnostics.log("dockerAuth: cookie not found after retries, load path directly: $path")
        }
        return "$scheme://$host$path"
    }

    fun credentialsFor(nasId: String): Pair<String, String>? {
        val nas = repo.nas(nasId) ?: return null
        val password = repo.passwordOf(nas) ?: return null
        return nas.username to password
    }

    fun saveNas(profile: NasProfile, plainPassword: String?, done: () -> Unit) {
        viewModelScope.launch {
            val normalized = profile.copy(
                connectionId = com.ugport.util.UrlTools.normalizeConnectionId(profile.connectionId),
                baseUrl = "",
            )
            repo.saveNas(normalized, plainPassword)
            container.sessions.reset(profile.id)
            container.uids.invalidate(profile.id)
            container.endpoints.invalidate(profile.id)
            done()
        }
    }

    fun deleteNas(id: String, done: () -> Unit) {
        viewModelScope.launch {
            repo.deleteNas(id)
            done()
        }
    }

    fun saveShortcut(shortcut: Shortcut, done: () -> Unit) {
        viewModelScope.launch {
            repo.saveShortcut(shortcut)
            done()
        }
    }

    fun deleteShortcut(id: String, done: () -> Unit) {
        viewModelScope.launch {
            repo.deleteShortcut(id)
            done()
        }
    }

    fun testLogin(nasId: String) {
        busy = true
        viewModelScope.launch {
            try {
                container.uids.invalidate(nasId)
                when (val session = container.sessions.ensureSession(nasId, force = true)) {
                    is SessionResult.Success -> {
                        val endpoints = container.endpoints.resolve(nasId)
                        message = if (endpoints == null) {
                            "登录成功，但未识别到服务器标识，请查看诊断日志"
                        } else {
                            "登录成功，服务器：${endpoints.relayDomain}"
                        }
                    }
                    is SessionResult.OtpRequired -> {
                        otpPrompt = OtpPrompt(nasId, session.tokenId, session.otpType, null)
                        message = "该账号需要二次验证"
                    }
                    is SessionResult.Failure -> message = session.message
                }
            } finally {
                busy = false
            }
        }
    }

    fun setExternalLinkMode(mode: String) {
        viewModelScope.launch { repo.setExternalLinkMode(mode) }
    }

    fun clearCookies() {
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
        message = "已清除 Cookie"
    }

    fun clearSession(nasId: String) {
        viewModelScope.launch {
            repo.clearToken(nasId)
            container.sessions.clearCachedToken(nasId)
            container.uids.invalidate(nasId)
            message = "已清除登录状态"
        }
    }
}
