package com.ugport.util

import com.ugport.data.model.NasProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlToolsLoginTest {

    private val relayHost = "my-nas.xx.ug.link"

    @Test
    fun dockerAppLoginRouteIsNotUgreenLogin() {
        assertFalse(
            UrlTools.isUgreenLoginUrl("https://app-28082-my-nas.xx.ugdocker.link/login", relayHost)
        )
        assertFalse(
            UrlTools.isUgreenLoginUrl("https://app-28082-my-nas.xx.ugdocker.link/#/login", relayHost)
        )
    }

    @Test
    fun ugosDesktopLoginIsDetected() {
        assertTrue(
            UrlTools.isUgreenLoginUrl("https://my-nas.xx.ug.link/desktop/#/login/account", relayHost)
        )
    }

    @Test
    fun normalDesktopRouteIsNotLogin() {
        assertFalse(
            UrlTools.isUgreenLoginUrl("https://my-nas.xx.ug.link/desktop/#/files", relayHost)
        )
    }

    @Test
    fun externalLoginIsNotDetected() {
        assertFalse(UrlTools.isUgreenLoginUrl("https://example.com/login", relayHost))
    }

    @Test
    fun loginHostBuiltFromProfile() {
        val nas = NasProfile(
            id = "1",
            name = "NAS",
            connectionId = "my-nas",
            relayDomain = "xx.ug.link",
            username = "u",
        )
        assertEquals("my-nas.xx.ug.link", UrlTools.loginHost(nas))
    }
}
