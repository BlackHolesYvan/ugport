package com.ugport.data

import com.ugport.data.model.AppData
import com.ugport.data.model.ThemeMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDataThemeModeTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun legacyJsonWithoutThemeModeDefaultsToSystem() {
        val raw = """{"schema":2,"nasList":[],"shortcuts":[],"externalLinkMode":"ask"}"""
        val data = json.decodeFromString<AppData>(raw)
        assertEquals(ThemeMode.SYSTEM, data.themeMode)
    }

    @Test
    fun unknownThemeModeFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.sanitize("auto"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.sanitize(null))
        assertEquals(ThemeMode.LIGHT, ThemeMode.sanitize(ThemeMode.LIGHT))
        assertEquals(ThemeMode.DARK, ThemeMode.sanitize(ThemeMode.DARK))
    }
}
