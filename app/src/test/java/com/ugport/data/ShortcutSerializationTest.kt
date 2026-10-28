package com.ugport.data

import com.ugport.data.model.Shortcut
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun oldShortcutDefaultsToMobilePage() {
        val old = """{"id":"s1","nasId":"n1","name":"qBittorrent","port":20180,"path":"","url":"","appendUid":true}"""
        val decoded = json.decodeFromString<Shortcut>(old)
        assertFalse(decoded.desktopUa)
        assertFalse(decoded.allowBackground)
    }

    @Test
    fun desktopFlagSurvivesRoundTrip() {
        val shortcut = Shortcut(
            id = "s1",
            nasId = "n1",
            name = "qBittorrent",
            port = 20180,
            desktopUa = true,
        )
        val decoded = json.decodeFromString<Shortcut>(json.encodeToString(shortcut))
        assertTrue(decoded.desktopUa)
    }

    @Test
    fun backgroundFlagSurvivesRoundTrip() {
        val shortcut = Shortcut(
            id = "s1",
            nasId = "n1",
            name = "qBittorrent",
            port = 20180,
            allowBackground = false,
        )
        val decoded = json.decodeFromString<Shortcut>(json.encodeToString(shortcut))
        assertFalse(decoded.allowBackground)
    }

    @Test
    fun enabledBackgroundFlagSurvivesRoundTrip() {
        val shortcut = Shortcut(
            id = "s1",
            nasId = "n1",
            name = "Ting Reader",
            port = 20260,
            allowBackground = true,
        )
        val decoded = json.decodeFromString<Shortcut>(json.encodeToString(shortcut))
        assertTrue(decoded.allowBackground)
    }
}
