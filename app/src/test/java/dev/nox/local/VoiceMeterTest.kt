package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class VoiceMeterTest {
    @Test fun quietSpeechIsVisibleAndSilenceIsQuiet() {
        assertEquals(0f, VoiceMeter.level(ShortArray(1600), 1600), 0f)
        assertEquals(0f, VoiceMeter.level(shortArrayOf(1), 1), 0f)
        assertTrue(VoiceMeter.level(ShortArray(1600) { 100 }, 1600) > .20f)
        assertTrue(VoiceMeter.level(ShortArray(1600) { 500 }, 1600) > .55f)
        assertEquals(1f, VoiceMeter.level(shortArrayOf(Short.MAX_VALUE), 1), 0f)
    }
    @Test fun respectsActualReadCount() {
        assertEquals(0f, VoiceMeter.level(shortArrayOf(0, Short.MAX_VALUE), 1), 0f)
        assertEquals(0f, VoiceMeter.level(shortArrayOf(), 0), 0f)
    }
    @Test fun attackIsFastAndReleaseRemainsSmooth() {
        val onset = VoiceMeter.follow(0f, 1f)
        assertTrue(onset > .8f)
        val decay = VoiceMeter.follow(onset, 0f)
        assertTrue(decay in .5f..onset)
        var rest = decay
        repeat(20) { rest = VoiceMeter.follow(rest, 0f) }
        assertTrue(rest < .01f)
    }
}
