package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class CalcPlanPermitTest {
    @Test fun exactSequenceRequiresVerificationBetweenActions() {
        val p = CalcPlanPermit("calc", 7, listOf("3","7","+"), 1000) { 0 }
        assertTrue(p.dispatch("calc", 7, "3"))
        assertFalse(p.allows("calc", 7, "7"))
        assertTrue(p.verified())
        assertTrue(p.dispatch("calc", 7, "7"))
        assertTrue(p.verified())
        assertTrue(p.dispatch("calc", 7, "+"))
        assertFalse(p.complete())
        assertTrue(p.verified())
        assertTrue(p.complete())
        assertFalse(p.allows("calc", 7, "+"))
    }
    @Test fun wrongKeyOrWindowCannotBeRetried() {
        val p = CalcPlanPermit("calc", 7, listOf("3"), 1000) { 0 }
        assertFalse(p.dispatch("calc", 8, "3"))
        assertFalse(p.dispatch("calc", 7, "3"))
        val q = CalcPlanPermit("calc", 7, listOf("3"), 1000) { 0 }
        assertFalse(q.dispatch("other", 7, "3"))
        assertFalse(q.dispatch("calc", 7, "3"))
    }
    @Test fun expirationAndRevocationAreTerminal() {
        var now = 0L
        val p = CalcPlanPermit("calc", 7, listOf("3"), 1000) { now }
        now = 1000
        assertFalse(p.dispatch("calc", 7, "3"))
        val q = CalcPlanPermit("calc", 7, listOf("3"), 2000) { now }
        q.revoke()
        assertFalse(q.dispatch("calc", 7, "3"))
    }
    @Test fun ambiguousDispatchCannotBeRepeated() {
        val p = CalcPlanPermit("calc", 7, listOf("3"), 1000) { 0 }
        assertTrue(p.dispatch("calc", 7, "3"))
        assertFalse(p.dispatch("calc", 7, "3"))
        assertFalse(p.verified())
        assertFalse(p.complete())
    }
}
