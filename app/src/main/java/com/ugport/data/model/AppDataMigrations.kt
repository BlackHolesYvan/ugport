package com.ugport.data.model

/** 配置结构升级。纯 Kotlin 实现，便于 JVM 单测。 */
object AppDataMigrations {

    const val CURRENT_SCHEMA = 2

    fun migrate(data: AppData): AppData {
        if (data.schema >= CURRENT_SCHEMA) return data
        return data.copy(
            schema = CURRENT_SCHEMA,
            // 升级时所有旧端口统一关闭后台播放，需要时由用户手动开启
            shortcuts = data.shortcuts.map { it.copy(allowBackground = false) },
        )
    }
}
