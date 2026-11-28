package dev.nox.local
import org.junit.Assert.*
import org.junit.Test

class AudioDiagTest {
    @Test fun silenceIsMeasuredBySamplesNotFrameCount() {
        val d = AudioDiag(16000,1000)
        assertEquals(100L,d.frame(ShortArray(1600),1600,1100).silentMs)
        val m = d.frame(ShortArray(800),800,1150)
        assertEquals(150L,m.silentMs)
        assertEquals(-96.0,m.rmsDb,0.001)
        assertNull(m.firstTextMs)
        assertEquals(0L,m.clockDeltaMs)
    }
    @Test fun detectsSignalAndClippingAndTracksRecognitionTiming() {
        val d = AudioDiag(16000,1000)
        d.frame(ShortArray(1600),1600,1100)
        d.recognized(1400,true,180)
        val m = d.frame(shortArrayOf(-32768,32767,10000),3,1400)
        assertEquals(32768,m.peak)
        assertEquals(2,m.clipped)
        assertEquals(0L,m.silentMs)
        assertEquals(400L,m.firstTextMs!!)
        assertEquals(180L,m.lastWordEndMs!!)
        assertTrue(m.rmsDb > -10)
    }
}
