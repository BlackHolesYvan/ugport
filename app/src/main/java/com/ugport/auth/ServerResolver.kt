package com.ugport.auth

import com.ugport.network.Diagnostics
import com.ugport.network.UgreenApi
import com.ugport.network.UgreenEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 通过连接标识（alias，例如 `my-nas`）解析当前服务器域名。
 *
 * 接口：`POST https://api.ugnas.com/api/p2p/v2/ta/nodeInfo/byAlias`，
 * 返回 `relayDomain=xx.ug.link`、`dockerDomain=xx.ugdocker.link`。
 *
 * 主机按 [UgreenEndpoints.RESOLVER_HOSTS] 顺序尝试，全部失败时返回最后一次结果，
 * 由调用方决定是否回退到本地保存的域名。
 */
class ServerResolver(
    private val client: OkHttpClient,
    private val diagnostics: Diagnostics,
) {
    data class Resolved(
        val relayDomain: String?,
        val dockerDomain: String?,
        val code: Int,
        val message: String,
    )

    suspend fun resolve(connectionId: String): Resolved = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("alias", connectionId) }
            .toString()
            .toRequestBody(JSON)
        var last = Resolved(null, null, -1, "解析失败")
        for (host in UgreenEndpoints.RESOLVER_HOSTS) {
            val request = Request.Builder()
                .url("https://$host${UgreenEndpoints.RESOLVER_PATH}")
                .post(body)
                .header("Content-Type", "application/json")
                .header("User-Agent", UgreenApi.DESKTOP_UA)
                .header("UG-Agent", "PC/WEB")
                .header("lang", "zh-CN")
                .header("Origin", UgreenEndpoints.PORTAL_ORIGIN)
                .header("Referer", UgreenEndpoints.PORTAL_REFERER)
                .build()
            val result = runCatching {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val root = json.parseToJsonElement(text).jsonObject
                    val code = root["code"]?.jsonPrimitive?.intOrNull ?: 0
                    val data = root["data"] as? JsonObject
                    Resolved(
                        relayDomain = data?.get("relayDomain")?.jsonPrimitive?.contentOrNull,
                        dockerDomain = data?.get("dockerDomain")?.jsonPrimitive?.contentOrNull,
                        code = code,
                        message = root["msg"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            }.getOrElse {
                Resolved(null, null, -1, it.message ?: "网络错误")
            }
            diagnostics.log("resolve: host=$host code=${result.code}")
            diagnostics.detail(
                "resolve: alias=$connectionId relay=${result.relayDomain} docker=${result.dockerDomain}"
            )
            if (result.code == 200 &&
                !result.relayDomain.isNullOrBlank() &&
                !result.dockerDomain.isNullOrBlank()
            ) {
                return@withContext result
            }
            last = result
        }
        last
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
