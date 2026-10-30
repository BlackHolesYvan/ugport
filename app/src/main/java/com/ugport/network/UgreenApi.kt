package com.ugport.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * 绿联云 HTTP API。
 *
 * 所有请求都带 Web 端一致的 [desktopHeaders]：服务端会校验 Client-Id / Client-Version / UG-Agent，
 * 缺任意一个都可能返回 4xx 或空 data。
 *
 * token 约定：服务端接受 query 形式的明文 token；放在 `X-Ugreen-Token` 头里会被判定非法
 * （该头需要 RSA 加密值）。
 *
 * 线程：所有 suspend 方法内部切到 [Dispatchers.IO]，调用方可以直接在主线程调用。
 */
class UgreenApi(
    private val client: OkHttpClient,
    baseUrl: String,
    private val diagnostics: Diagnostics,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class LoginResult(
        val token: String?,
        val authType: String?,
        val enableOtp: Boolean,
        val tokenId: String?,
        val otpType: Int?,
        val code: Int,
        val message: String,
    )

    data class UidResult(val uid: String?, val code: Int, val message: String)

    data class DockerRedirect(val url: String?, val code: Int, val message: String)

    /**
     * 获取登录公钥。
     *
     * 服务端把 X-Rsa-Token 放进响应头（Base64）。
     * 返回 null 表示没拿到公钥，调用方不应继续登录。
     */
    suspend fun check(username: String): String? = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("username", username) }
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url("$baseUrl${UgreenEndpoints.VERIFY_CHECK}")
            .post(body)
            .desktopHeaders(baseUrl)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val header = response.header("X-Rsa-Token")
                diagnostics.log(
                    "check http=${response.code} " +
                        "rsaToken=${if (header.isNullOrBlank()) "none" else "present"}"
                )
                if (header.isNullOrBlank()) return@use null
                val decoded = runCatching {
                    String(Base64.decode(header, Base64.DEFAULT), Charsets.UTF_8)
                }.getOrDefault(header)
                RsaCipher.normalize(decoded)
            }
        }.getOrElse {
            diagnostics.log("check failed: ${it.message}")
            null
        }
    }

    /**
     * 用户名 + 密码登录。
     *
     * [LoginResult.token] 为空且 [LoginResult.enableOtp] 为 true 时，调用方应转入验证码流程。
     */
    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val publicKey = check(username)
            ?: return@withContext LoginResult(null, null, false, null, null, -1, "获取 RSA 公钥失败")
        val encrypted = runCatching { RsaCipher.encrypt(publicKey, password) }
            .getOrElse {
                diagnostics.log("rsa encrypt failed: ${it.message}")
                return@withContext LoginResult(null, null, false, null, null, -1, "密码加密失败")
            }
        val body = buildJsonObject {
            put("username", username)
            put("password", encrypted)
            put("keepalive", true)
            put("otp", true)
            // 该 NAS 使用简单密码模式；与 Web 端登录请求保持一致
            put("is_simple", true)
        }.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("$baseUrl${UgreenEndpoints.VERIFY_LOGIN}")
            .post(body)
            .desktopHeaders(baseUrl)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val env = parseEnvelope(text)
                val data = env.data as? JsonObject
                val token = data?.get("token")?.stringValue()
                    ?: (env.data as? JsonPrimitive)?.contentOrNull
                val enableOtp = data?.get("enable_otp").boolValue()
                    ?: (env.data as? JsonPrimitive)?.booleanOrNull
                    ?: false
                val tokenId = data?.get("token_id")?.stringValue()
                val otpType = data?.get("otp_type").intValue()
                    ?: data?.get("type").intValue()
                    ?: 1
                val authType = data?.get("auth_type")?.stringValue()
                val publicKey = data?.get("public_key")?.stringValue()
                diagnostics.log(
                    "login http=${response.code} code=${env.code} msg=${env.msg} " +
                        "otp=$enableOtp token=${diagnostics.maskToken(token)}"
                )
                LoginResult(token, authType, enableOtp, tokenId, otpType, env.code, env.msg)
            }
        }.getOrElse {
            diagnostics.log("login failed: ${it.message}")
            LoginResult(null, null, false, null, null, -1, it.message ?: "网络错误")
        }
    }

    /**
     * OTP 验证码登录。
     * [trust] 为 true 时服务端会记住当前设备，后续登录通常不再需要验证码。
     */
    suspend fun codeLogin(code: String, type: Int?, tokenId: String?): LoginResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("code", code)
                put("type", type ?: 1)
                if (!tokenId.isNullOrBlank()) put("token_id", tokenId)
                put("trust_info", buildJsonObject {
                    put("client_type", "web")
                    put("system", "windows")
                    put("dev_name", "Chrome/131.0.0.0")
                })
                // 验证通过后直接信任当前设备，减少后续 OTP
                put("trust", true)
            }.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl${UgreenEndpoints.VERIFY_CODE_LOGIN}")
                .post(body)
                .desktopHeaders(baseUrl)
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    val env = parseEnvelope(text)
                    val data = env.data as? JsonObject
                    val token = data?.get("token")?.stringValue()
                    diagnostics.log(
                        "codeLogin http=${response.code} code=${env.code} msg=${env.msg} " +
                            "token=${diagnostics.maskToken(token)}"
                    )
                    LoginResult(
                        token,
                        data?.get("auth_type")?.stringValue(),
                        false,
                        data?.get("token_id")?.stringValue(),
                        data?.get("otp_type").intValue(),
                        env.code,
                        env.msg,
                    )
                }
            }.getOrElse {
                loginError(it)
            }
        }

    /** 校验 token 是否仍有效。正常打开端口不走这里，仅供诊断与手动重试使用。 */
    suspend fun isLogin(token: String, authType: String?): Boolean = withContext(Dispatchers.IO) {
        val url = urlBuilder(UgreenEndpoints.VERIFY_IS_LOGIN, token, authType).build()
        val request = Request.Builder()
            .url(url)
            .get()
            .desktopHeaders(baseUrl)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val env = parseEnvelope(text)
                diagnostics.log("isLogin http=${response.code} code=${env.code} msg=${env.msg}")
                response.code == 200 && env.code == 200
            }
        }.getOrElse {
            diagnostics.log("isLogin failed: ${it.message}")
            false
        }
    }

    /** 刷新 token。失败返回 null，调用方应继续使用旧 token 而不是清空登录态。 */
    suspend fun refresh(token: String, authType: String?): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlBuilder(UgreenEndpoints.VERIFY_REFRESH, token, authType).build())
            .put(ByteArray(0).toRequestBody(null))
            .desktopHeaders(baseUrl)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val env = parseEnvelope(text)
                diagnostics.log("refresh http=${response.code} code=${env.code} msg=${env.msg}")
                if (response.code == 200 && env.code == 200) {
                    (env.data as? JsonObject)?.get("token")?.stringValue() ?: token
                } else {
                    null
                }
            }
        }.getOrElse { null }
    }

    /** 获取网关端口需要的 uid；返回结构在不同固件上可能是 `data.result` 或 `data.uid`。 */
    suspend fun getUid(token: String, authType: String?): UidResult = withContext(Dispatchers.IO) {
        val url = urlBuilder(UgreenEndpoints.DOCKER_UID, token, authType).build()
        val request = Request.Builder()
            .url(url)
            .get()
            .desktopHeaders(baseUrl)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val env = parseEnvelope(text)
                val uid = extractUid(env.data)
                diagnostics.log(
                    "GetUid http=${response.code} code=${env.code} msg=${env.msg} " +
                        "uid=${diagnostics.maskToken(uid)}"
                )
                UidResult(uid, env.code, env.msg)
            }
        }.getOrElse {
            diagnostics.log("GetUid failed: ${it.message}")
            UidResult(null, -1, it.message ?: "网络错误")
        }
    }

    /**
     * Docker 容器端口（app-<port>-xxx.ugdocker.link）的访问方式：
     * GET /ugreen/v1/gateway/proxy/dockerToken?port=<port> -> data.redirect_url
     * 打开 redirect_url 后页面会写入 ugreen-proxy-token Cookie 并跳转到 /。
     */
    suspend fun getDockerRedirect(port: Int, token: String): DockerRedirect = withContext(Dispatchers.IO) {
        val builder = ("$baseUrl${UgreenEndpoints.DOCKER_TOKEN}").toHttpUrlOrNull()?.newBuilder()
        if (builder == null) return@withContext DockerRedirect(null, -1, "非法地址")
        builder.addQueryParameter("port", port.toString())
        builder.addQueryParameter("token", token)
        val request = Request.Builder()
            .url(builder.build())
            .get()
            .desktopHeaders(baseUrl)
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val env = parseEnvelope(text)
                val redirect = (env.data as? JsonObject)?.get("redirect_url")?.stringValue()
                diagnostics.log(
                    "dockerToken port=$port http=${response.code} code=${env.code} msg=${env.msg} " +
                        "redirect=${if (redirect.isNullOrBlank()) "none" else "present"}"
                )
                DockerRedirect(redirect, env.code, env.msg)
            }
        }.getOrElse {
            diagnostics.log("dockerToken failed: ${it.message}")
            DockerRedirect(null, -1, it.message ?: "网络错误")
        }
    }

    suspend fun logout(token: String, authType: String?) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(urlBuilder(UgreenEndpoints.VERIFY_LOGOUT, token, authType).build())
                .post(ByteArray(0).toRequestBody(null))
                .desktopHeaders(baseUrl)
                .build()
            runCatching { client.newCall(request).execute().close() }
        }
    }

    private fun urlBuilder(path: String, token: String, authType: String?): HttpUrl.Builder {
        val builder = ("$baseUrl$path").toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("非法地址：$baseUrl$path")
        // 实测：服务端对 is_login / GetUid 都接受 query 形式的明文 token；
        // 明文放在 X-Ugreen-Token 头会被判定为非法（该头需要 RSA 加密值）。
        builder.addQueryParameter("token", token)
        return builder
    }

    private fun loginError(error: Throwable) = LoginResult(
        null,
        null,
        false,
        null,
        null,
        -1,
        error.message ?: "网络错误",
    )

    private fun parseEnvelope(text: String): Envelope {
        return runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            Envelope(
                code = obj["code"]?.jsonPrimitive?.intOrNull ?: 0,
                msg = obj["msg"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                data = obj["data"],
            )
        }.getOrDefault(Envelope(0, "", null))
    }

    private fun extractUid(data: JsonElement?): String? {
        val obj = data as? JsonObject ?: return null
        val value = obj["result"] ?: obj["uid"] ?: return null
        if (value is JsonPrimitive) return value.contentOrNull
        if (value is JsonObject) {
            return value["uid"]?.stringValue()
                ?: value["result"]?.stringValue()
                ?: value["token"]?.stringValue()
        }
        return null
    }

    private fun JsonElement?.stringValue(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.boolValue(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: primitive.intOrNull?.let { it != 0 }
    }

    private fun JsonElement?.intValue(): Int? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }

    private data class Envelope(val code: Int, val msg: String, val data: JsonElement?)

    private object ClientInfo {
        val id: String = "${UUID.randomUUID()}-WEB"
        const val VERSION = "78578"
        const val VERSION_STR = "1.20.0"
    }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun Request.Builder.desktopHeaders(baseUrl: String): Request.Builder = apply {
            header("User-Agent", DESKTOP_UA)
            header("Accept", "application/json, text/plain, */*")
            header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            header("Origin", baseUrl)
            header("Referer", "$baseUrl/desktop/")
            // 实测服务端会校验客户端标识；这些值来自 UGOS Pro Web 端
            header("Client-Id", ClientInfo.id)
            header("Client-Version", ClientInfo.VERSION)
            header("Client-Version-Str", ClientInfo.VERSION_STR)
            header("UG-Agent", "PC/WEB")
            header("X-Specify-Language", "zh-CN")
        }
    }
}
