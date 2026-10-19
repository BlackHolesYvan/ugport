package com.ugport.di

import android.content.Context
import com.ugport.auth.EndpointResolver
import com.ugport.auth.ServerResolver
import com.ugport.auth.SessionManager
import com.ugport.auth.ShortcutOpener
import com.ugport.auth.UidProvider
import com.ugport.data.crypto.KeystoreCipher
import com.ugport.data.repo.ProfileRepository
import com.ugport.data.store.ProfileStore
import com.ugport.network.Diagnostics
import com.ugport.network.HttpFactory
import com.ugport.network.UgreenApi

class AppContainer(context: Context) {

    val diagnostics = Diagnostics()
    private val cipher = KeystoreCipher()
    private val store = ProfileStore(context.applicationContext)
    private val http = HttpFactory()

    val repo = ProfileRepository(store, cipher)

    private val apiFactory: (String) -> UgreenApi = { baseUrl ->
        UgreenApi(http.client, baseUrl, diagnostics)
    }

    val dockerAuth = com.ugport.auth.DockerAuthExchange(http.client, diagnostics)
    private val serverResolver = ServerResolver(http.client, diagnostics)
    val endpoints = EndpointResolver(repo, serverResolver, diagnostics)
    val sessions = SessionManager(repo, apiFactory, endpoints, diagnostics)
    val uids = UidProvider(repo, sessions, apiFactory, endpoints, diagnostics)
    val opener = ShortcutOpener(repo, sessions, uids, apiFactory, endpoints, diagnostics)

    val cookieJar: HttpFactory.MemoryCookieJar
        get() = http.client.cookieJar as HttpFactory.MemoryCookieJar
}
