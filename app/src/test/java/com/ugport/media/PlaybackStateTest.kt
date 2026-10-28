package com.ugport.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {

    @Test
    fun parsesPlayingAudio() {
        val snapshot = MediaStateParser.parse(
            """{"v":1,"has":true,"playing":true,"state":"playing","pos":123000,"dur":240000,""" +
                """"rate":1,"kind":"audio","title":"Track","artist":"Artist","album":"Album"}"""
        )
        requireNotNull(snapshot)
        assertEquals(PlaybackPhase.PLAYING, snapshot.phase)
        assertEquals(123000L, snapshot.positionMs)
        assertEquals(240000L, snapshot.durationMs)
        assertEquals("Track", snapshot.title)
        assertEquals("Artist", snapshot.artist)
        assertEquals("Album", snapshot.album)
        assertTrue(snapshot.playing)
    }

    @Test
    fun ignoresUnknownFields() {
        val snapshot = MediaStateParser.parse("""{"playing":true,"state":"playing","extra":{"a":1}}""")
        assertEquals(PlaybackPhase.PLAYING, requireNotNull(snapshot).phase)
    }

    @Test
    fun mapsBuffering() {
        val snapshot = MediaStateParser.parse("""{"playing":true,"state":"buffering","dur":0}""")
        assertEquals(PlaybackPhase.BUFFERING, requireNotNull(snapshot).phase)
        assertTrue(requireNotNull(snapshot).playing)
    }

    @Test
    fun mapsPausedEndedErrorNone() {
        assertEquals(PlaybackPhase.PAUSED, requireNotNull(MediaStateParser.parse("""{"state":"paused"}""")).phase)
        assertEquals(PlaybackPhase.ENDED, requireNotNull(MediaStateParser.parse("""{"state":"ended"}""")).phase)
        assertEquals(PlaybackPhase.ERROR, requireNotNull(MediaStateParser.parse("""{"state":"error"}""")).phase)
        assertEquals(PlaybackPhase.NONE, requireNotNull(MediaStateParser.parse("""{"state":"none"}""")).phase)
    }

    @Test
    fun clampsAndSanitizes() {
        val snapshot = requireNotNull(
            MediaStateParser.parse("""{"playing":true,"pos":-5,"dur":1000,"title":"a
b"}""")
        )
        assertEquals(0L, snapshot.positionMs)
        assertEquals("a b", snapshot.title)
    }

    @Test
    fun truncatesLongText() {
        val longTitle = "x".repeat(500)
        val snapshot = requireNotNull(
            MediaStateParser.parse("""{"playing":true,"state":"playing","title":"$longTitle"}""")
        )
        assertEquals(160, snapshot.title.length)
    }

    @Test
    fun rejectsMalformedAndOversized() {
        assertNull(MediaStateParser.parse(null))
        assertNull(MediaStateParser.parse(""))
        assertNull(MediaStateParser.parse("not json"))
        assertNull(MediaStateParser.parse("""{"playing":"yes"}"""))
        assertNull(MediaStateParser.parse("x".repeat(5000)))
    }

    @Test
    fun zeroDurationIsNotSeekable() {
        val snapshot = requireNotNull(
            MediaStateParser.parse("""{"playing":true,"state":"playing","pos":500,"dur":0}""")
        )
        assertEquals(0L, snapshot.durationMs)
        assertEquals(500L, snapshot.positionMs)
    }

    @Test
    fun parsesSkipCapabilities() {
        val snapshot = requireNotNull(
            MediaStateParser.parse("""{"playing":true,"state":"playing","canPrev":true,"canNext":true}""")
        )
        assertTrue(snapshot.canPrev)
        assertTrue(snapshot.canNext)
    }

    @Test
    fun skipCapabilitiesDefaultToFalse() {
        val snapshot = requireNotNull(
            MediaStateParser.parse("""{"playing":true,"state":"playing"}""")
        )
        assertFalse(snapshot.canPrev)
        assertFalse(snapshot.canNext)
    }

    @Test
    fun keepsCapabilitiesWithoutMediaElement() {
        val snapshot = requireNotNull(
            MediaStateParser.parse(
                """{"v":4,"has":false,"playing":false,"state":"none","canNext":true}"""
            )
        )
        assertEquals(PlaybackPhase.NONE, snapshot.phase)
        assertTrue(snapshot.canNext)
        assertFalse(snapshot.canPrev)
    }
}
