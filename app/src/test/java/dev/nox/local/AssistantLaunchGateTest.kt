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
    @Test fun draftOnlyDeliveredToValidTicketOnce() {
        val g = Fixture().gate
        val ticket = g.issueDraft(true, true, "Проверка диктовки")
        assertNotNull(ticket)
        assertNull(g.consumeDraft("forged", true, true))
        assertEquals("Проверка диктовки", g.consumeDraft(ticket, true, true))
        assertNull(g.consumeDraft(ticket, true, true))
    }

    @Test fun draftExpiredOrLockedIsNeverDelivered() {
        val f = Fixture()
        val ticket = f.gate.issueDraft(true, true, "Текст")
        f.now = 5000L
        assertNull(f.gate.consumeDraft(ticket, true, true))
        val second = f.gate.issueDraft(true, true, "Другой")
        assertNull(f.gate.consumeDraft(second, true, false))
        assertNull(f.gate.consumeDraft(second, true, true))
    }

    @Test fun oldExpiryDoesNotEraseNewDraft() {
        val g = Fixture().gate
        val first = checkNotNull(g.issueDraft(true, true, "Старый"))
        val second = g.issueDraft(true, true, "Новый")
        g.expire(first)
        assertEquals("Новый", g.consumeDraft(second, true, true))
    }

    @Test fun blankOrOversizedDraftIsBounded() {
        val g = Fixture().gate
        assertNull(g.issueDraft(true, true, "  "))
        val t = g.issueDraft(true, true, "x".repeat(3000))
        assertEquals(2000, g.consumeDraft(t, true, true)?.length)
    }
}
