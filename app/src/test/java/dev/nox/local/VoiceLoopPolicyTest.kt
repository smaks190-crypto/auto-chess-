package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class VoiceLoopPolicyTest {
    @Test fun resumesOnlyInIdleUnlockedSession() {
        assertTrue(VoiceLoopPolicy.canListen(true, false, false, false, true, false))
        assertFalse(VoiceLoopPolicy.canListen(false, false, false, false, true, false))
        assertFalse(VoiceLoopPolicy.canListen(true, true, false, false, true, false))
        assertFalse(VoiceLoopPolicy.canListen(true, false, true, false, true, false))
        assertFalse(VoiceLoopPolicy.canListen(true, false, false, true, true, false))
        assertFalse(VoiceLoopPolicy.canListen(true, false, false, false, false, false))
        assertFalse(VoiceLoopPolicy.canListen(true, false, false, false, true, true))
    }
    @Test fun retriesAreBoundedAndFatalErrorsStop() {
        assertEquals(400L, VoiceLoopPolicy.retryDelay(7, 1))
        assertEquals(400L, VoiceLoopPolicy.retryDelay(6, 1))
        assertEquals(8000L, VoiceLoopPolicy.retryDelay(8, 99))
        assertNull(VoiceLoopPolicy.retryDelay(9, 1))
        assertNull(VoiceLoopPolicy.retryDelay(12, 1))
        assertNull(VoiceLoopPolicy.retryDelay(13, 1))
    }
}
