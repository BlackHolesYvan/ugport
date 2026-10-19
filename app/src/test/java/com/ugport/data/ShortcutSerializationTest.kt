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
}
