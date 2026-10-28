package com.ugport.data.repo

import com.ugport.data.crypto.KeystoreCipher
import com.ugport.data.model.AppData
import com.ugport.data.model.NasProfile
import com.ugport.data.model.Shortcut
import com.ugport.data.model.ThemeMode
import com.ugport.data.store.ProfileStore
import com.ugport.util.UrlTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileRepository(
    private val store: ProfileStore,
    private val cipher: KeystoreCipher,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AppData())
    val state: StateFlow<AppData> = _state.asStateFlow()
    val data: Flow<AppData> = store.data

    init {
        scope.launch { store.data.collect { _state.value = it } }
    }

    fun nas(id: String?): NasProfile? = _state.value.nasList.firstOrNull { it.id == id }

    fun shortcutsOf(nasId: String): List<Shortcut> =
        _state.value.shortcuts.filter { it.nasId == nasId }

    fun shortcut(id: String?): Shortcut? = _state.value.shortcuts.firstOrNull { it.id == id }

    suspend fun setExternalLinkMode(mode: String) {
        store.update { it.copy(externalLinkMode = mode) }
    }

    suspend fun setThemeMode(mode: String) {
        store.update { it.copy(themeMode = ThemeMode.sanitize(mode)) }
    }

    suspend fun saveNas(profile: NasProfile, plainPassword: String?) {
        store.update { data ->
            val existing = data.nasList.firstOrNull { it.id == profile.id }
            val oldConnectionId = existing?.let {
                UrlTools.normalizeConnectionId(
                    it.connectionId.ifBlank { UrlTools.parseConnectionId(it.baseUrl) }
                )
            }
            val newConnectionId = UrlTools.normalizeConnectionId(
                profile.connectionId.ifBlank { UrlTools.parseConnectionId(profile.baseUrl) }
            )
            val sameNas = existing != null && oldConnectionId == newConnectionId

            val passwordEnc = when {
                plainPassword != null -> cipher.encrypt(plainPassword, passwordAad(profile.id))
                existing != null -> existing.passwordEnc
                else -> ""
            }
            // 编辑 NAS 时始终保留已保存的长期 token；只有连接标识变更时才清空。
            val tokenEnc = if (sameNas && existing != null) existing.tokenEnc else ""
            val tokenSavedAt = if (sameNas && existing != null) existing.tokenSavedAt else 0L
            val authType = if (sameNas && existing != null) existing.authType else null
            val relayDomain = if (sameNas && existing != null) {
                existing.relayDomain ?: profile.relayDomain
            } else {
                profile.relayDomain
            }
            val dockerDomain = if (sameNas && existing != null) {
                existing.dockerDomain ?: profile.dockerDomain
            } else {
                profile.dockerDomain
            }
            val resolvedAt = if (sameNas && existing != null) {
                maxOf(existing.resolvedAt, profile.resolvedAt)
            } else {
                profile.resolvedAt
            }

            val merged = profile.copy(
                passwordEnc = passwordEnc,
                tokenEnc = tokenEnc,
                tokenSavedAt = tokenSavedAt,
                authType = authType,
                relayDomain = relayDomain,
                dockerDomain = dockerDomain,
                resolvedAt = resolvedAt,
            )
            val list = data.nasList.filterNot { it.id == profile.id } + merged
            data.copy(nasList = list)
        }
    }

    suspend fun deleteNas(id: String) {
        store.update { data ->
            data.copy(
                nasList = data.nasList.filterNot { it.id == id },
                shortcuts = data.shortcuts.filterNot { it.nasId == id },
            )
        }
    }

    suspend fun saveShortcut(shortcut: Shortcut) {
        store.update { data ->
            val list = data.shortcuts.filterNot { it.id == shortcut.id } + shortcut
            data.copy(shortcuts = list)
        }
    }

    suspend fun deleteShortcut(id: String) {
        store.update { data -> data.copy(shortcuts = data.shortcuts.filterNot { it.id == id }) }
    }

    fun passwordOf(nas: NasProfile): String? =
        cipher.decrypt(nas.passwordEnc, passwordAad(nas.id))

    fun tokenOf(nas: NasProfile): String? =
        cipher.decrypt(nas.tokenEnc, tokenAad(nas.id))

    suspend fun saveToken(nasId: String, token: String, authType: String?) {
        store.update { data ->
            data.copy(
                nasList = data.nasList.map { nas ->
                    if (nas.id == nasId) {
                        nas.copy(
                            tokenEnc = cipher.encrypt(token, tokenAad(nasId)),
                            tokenSavedAt = System.currentTimeMillis(),
                            authType = authType ?: nas.authType,
                        )
                    } else {
                        nas
                    }
                }
            )
        }
    }

    suspend fun updateResolution(nasId: String, relayDomain: String, dockerDomain: String) {
        store.update { data ->
            data.copy(
                nasList = data.nasList.map { nas ->
                    if (nas.id == nasId) {
                        nas.copy(
                            relayDomain = relayDomain,
                            dockerDomain = dockerDomain,
                            resolvedAt = System.currentTimeMillis(),
                        )
                    } else {
                        nas
                    }
                }
            )
        }
    }

    suspend fun clearToken(nasId: String) {
        store.update { data ->
            data.copy(
                nasList = data.nasList.map { nas ->
                    if (nas.id == nasId) nas.copy(tokenEnc = "", tokenSavedAt = 0L) else nas
                }
            )
        }
    }

    private fun passwordAad(nasId: String) = "nas:$nasId:password"
    private fun tokenAad(nasId: String) = "nas:$nasId:token"
}
