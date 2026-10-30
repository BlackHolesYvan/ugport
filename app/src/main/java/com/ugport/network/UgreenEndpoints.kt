package com.ugport.network

/**
 * 绿联云 API 的固定入口。
 *
 * 这些地址来自 UGOS Pro Web 端的实际请求。接口路径随官方版本变化，
 * 修改时请同步更新 README 的「工作原理」与 `tools/verify_ugreen.py`。
 */
object UgreenEndpoints {

    /** 连接标识解析接口的备用主机，按顺序尝试。 */
    val RESOLVER_HOSTS = listOf("api.ugnas.com", "api-zh.ugnas.com")

    const val RESOLVER_PATH = "/api/p2p/v2/ta/nodeInfo/byAlias"

    /** 网页端要求的 Origin / Referer，缺失时解析接口会拒绝。 */
    const val PORTAL_ORIGIN = "https://www.ug.link"
    const val PORTAL_REFERER = "https://www.ug.link/"

    const val VERIFY_CHECK = "/ugreen/v1/verify/check"
    const val VERIFY_LOGIN = "/ugreen/v1/verify/login"
    const val VERIFY_CODE_LOGIN = "/ugreen/v1/verify/code/login"
    const val VERIFY_IS_LOGIN = "/ugreen/v1/verify/is_login"
    const val VERIFY_REFRESH = "/ugreen/v1/verify/refresh"
    const val VERIFY_LOGOUT = "/ugreen/v1/verify/logout"

    const val DOCKER_UID = "/ugreen/v1/docker/app/GetUid"
    const val DOCKER_TOKEN = "/ugreen/v1/gateway/proxy/dockerToken"

    /** 连接标识解析失败、且本地没有历史域名时使用的默认中转域名。 */
    const val DEFAULT_RELAY_DOMAIN = "ug.link"
}
