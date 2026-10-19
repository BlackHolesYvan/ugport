package com.ugport.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class HttpFactory {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(MemoryCookieJar())
        .build()

    class MemoryCookieJar : CookieJar {
        private val cookies = CopyOnWriteArrayList<Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { incoming ->
                this.cookies.removeAll {
                    it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
                }
                this.cookies.add(incoming)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { it.matches(url) }

        fun header(url: HttpUrl): String =
            loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }

        fun all(): List<Cookie> = cookies.toList()
    }
}
