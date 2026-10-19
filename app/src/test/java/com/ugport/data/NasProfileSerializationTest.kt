package com.ugport.data

import com.ugport.data.model.NasProfile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class NasProfileSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun tokenFieldsSurviveRoundTrip() {
        val nas = NasProfile(
            id = "1",
            name = "NAS",
            connectionId = "my-nas",
            username = "test",
            passwordEnc = "p",
            tokenEnc = "t",
            tokenSavedAt = 123L,
            authType = "header",
            relayDomain = "xx.ug.link",
            dockerDomain = "xx.ugdocker.link",
            resolvedAt = 456L,
        )

        val decoded = json.decodeFromString<NasProfile>(json.encodeToString(nas))

        assertEquals("t", decoded.tokenEnc)
        assertEquals(123L, decoded.tokenSavedAt)
        assertEquals("header", decoded.authType)
        assertEquals("xx.ug.link", decoded.relayDomain)
        assertEquals("xx.ugdocker.link", decoded.dockerDomain)
    }

    @Test
    fun oldProfileJsonStillLoadsToken() {
        val old = """
            {"id":"1","name":"NAS","baseUrl":"https://my-nas.xx.ug.link",
             "username":"test","passwordEnc":"p","tokenEnc":"t","tokenSavedAt":123}
        """.trimIndent()

        val decoded = json.decodeFromString<NasProfile>(old)

        assertEquals("", decoded.connectionId)
        assertEquals("t", decoded.tokenEnc)
        assertEquals(123L, decoded.tokenSavedAt)
    }
}
