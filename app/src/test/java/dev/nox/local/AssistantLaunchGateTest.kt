package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AssistantLaunchGateTest {
    private class Fixture {
        var now = 0L
        var count = 0
        val gate = AssistantLaunchGate({ now }, { "ticket-" + ++count }, 5000L)
    }
    @Test fun noTicketOrForgedTicketCannotActivate() {
        val g = Fixture().gate
        assertFalse(g.consume(null, true, true))
        val t = g.issue(true, true)
        assertFalse(g.consume("fake", true, true))
        assertTrue(g.consume(t, true, true))
    }
    @Test fun ticketIsSingleUse() {
        val g = Fixture().gate
        val t = g.issue(true, true)
        assertTrue(g.consume(t, true, true))
        assertFalse(g.consume(t, true, true))
    }
    @Test fun issuanceRequiresRoleAndUnlock() {
        val g = Fixture().gate
        assertNull(g.issue(false, true))
        assertNull(g.issue(true, false))
    }
    @Test fun expiresAtDeadline() {
        val f = Fixture()
        val t = f.gate.issue(true, true)
        f.now = 5000L
        assertFalse(f.gate.consume(t, true, true))
    }
    @Test fun losingRoleOrLockingRevokesTicket() {
        val g = Fixture().gate
        val a = g.issue(true, true)
        assertFalse(g.consume(a, false, true))
        assertFalse(g.consume(a, true, true))
        val b = g.issue(true, true)
        assertFalse(g.consume(b, true, false))
        assertFalse(g.consume(b, true, true))
    }
    @Test fun newInvocationReplacesOldAndShutdownRevokes() {
        val g = Fixture().gate
        val a = g.issue(true, true)
        val b = g.issue(true, true)
        assertFalse(g.consume(a, true, true))
        assertTrue(g.consume(b, true, true))
        val c = g.issue(true, true)
        g.revoke()
        assertFalse(g.consume(c, true, true))
    }
}
