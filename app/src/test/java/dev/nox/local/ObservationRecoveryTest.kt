package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class ObservationRecoveryTest {
    @Test fun lateRebindExtendsReadOnlyWindowOnce() {
        var now = 0L
        val wait = ObservationRecovery { now }
        assertEquals(3000L, wait.remaining())
        now = 2850
        assertTrue(wait.reconnecting())
        now = 4100
        assertEquals(2900L, wait.remaining())
        now = 5000
        assertTrue(wait.reconnecting())
        assertEquals(2000L, wait.remaining())
        now = 7000
        assertEquals(0L, wait.remaining())
        assertFalse(wait.reconnecting())
    }
    @Test fun noServiceLossKeepsOriginalDeadline() {
        var now = 0L
        val wait = ObservationRecovery { now }
        now = 3000
        assertEquals(0L, wait.remaining())
        assertFalse(wait.reconnecting())
    }
}
