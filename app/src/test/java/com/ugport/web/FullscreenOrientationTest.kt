package com.ugport.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullscreenOrientationTest {

    @Test
    fun landscapeRatiosUseLandscape() {
        assertEquals(FullscreenOrientation.LANDSCAPE, resolveFullscreenOrientation(16f / 9f))
        assertEquals(FullscreenOrientation.LANDSCAPE, resolveFullscreenOrientation(4f / 3f))
    }

    @Test
    fun portraitRatiosUsePortrait() {
        assertEquals(FullscreenOrientation.PORTRAIT, resolveFullscreenOrientation(9f / 16f))
        assertEquals(FullscreenOrientation.PORTRAIT, resolveFullscreenOrientation(3f / 4f))
    }

    @Test
    fun squareAndUnknownUseSensor() {
        assertEquals(FullscreenOrientation.SENSOR, resolveFullscreenOrientation(1f))
        assertEquals(FullscreenOrientation.SENSOR, resolveFullscreenOrientation(null))
        assertEquals(FullscreenOrientation.SENSOR, resolveFullscreenOrientation(Float.NaN))
        assertEquals(FullscreenOrientation.SENSOR, resolveFullscreenOrientation(0f))
    }

    @Test
    fun parseVideoAspectHandlesQuotedSize() {
        assertEquals(16f / 9f, requireNotNull(parseVideoAspect("\"1280x720\"")), 0.001f)
        assertEquals(9f / 16f, requireNotNull(parseVideoAspect("720x1280")), 0.001f)
    }

    @Test
    fun parseVideoAspectRejectsInvalidValues() {
        assertNull(parseVideoAspect(null))
        assertNull(parseVideoAspect(""))
        assertNull(parseVideoAspect("\"\""))
        assertNull(parseVideoAspect("\"0x720\""))
        assertNull(parseVideoAspect("abc"))
        assertNull(parseVideoAspect("1280x"))
        assertNull(parseVideoAspect("1280x720x2"))
    }
}
