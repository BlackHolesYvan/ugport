package com.ugport.data.model

import kotlinx.serialization.Serializable

@Serializable
data class NasProfile(
    val id: String,
    val name: String,
    val connectionId: String = "",
    val relayDomain: String? = null,
    val dockerDomain: String? = null,
    val resolvedAt: Long = 0L,
    // 兼容 1.2 及以前保存的完整地址；新配置只使用 connectionId
    val baseUrl: String = "",
    val username: String,
    val passwordEnc: String = "",
    val tokenEnc: String = "",
    val tokenSavedAt: Long = 0L,
    val authType: String? = null,
    val autoLogin: Boolean = true,
)

@Serializable
data class Shortcut(
    val id: String,
    val nasId: String,
    val name: String,
    val port: Int = 0,
    val path: String = "",
    // 兼容旧版本的完整地址
    val url: String = "",
    val appendUid: Boolean = true,
    /** 页面使用桌面端 UA 与桌面布局；false 时按移动端页面加载。 */
    val desktopUa: Boolean = false,
    /**
     * 允许切到后台/锁屏后继续播放。
     * 默认关闭：不注入媒体探测、不启动媒体前台服务，离开前台会暂停页面音频。
     */
    val allowBackground: Boolean = false,
)

@Serializable
data class AppData(
    val schema: Int = 2,
    val nasList: List<NasProfile> = emptyList(),
    val shortcuts: List<Shortcut> = emptyList(),
    /** 外部链接打开方式：ask（每次询问）/ internal（应用内）/ external（浏览器）。 */
    val externalLinkMode: String = "ask",
)
