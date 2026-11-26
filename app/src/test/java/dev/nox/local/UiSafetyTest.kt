package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class UiSafetyTest {
    private val expected = NodeIdentity("calc", "id/btn_3", "Button", "3", null,
        "[0,0][10,10]", true, true, false, true, false, false)

    @Test fun identicalNodeDispatchesOnce() {
        var calls = 0
        assertTrue(withVerifiedNode(expected, expected) { calls++; true })
        assertEquals(1, calls)
    }

    private fun reject(actual: NodeIdentity, reason: String) {
        var calls = 0
        try {
            withVerifiedNode(expected, actual) { calls++ }
            fail("Expected NODE_CHANGED:$reason")
        } catch (e: NodeChanged) { assertEquals(reason, e.reason) }
        assertEquals(0, calls)
    }

    @Test fun siblingAtOldPathNeverReceivesClick() = reject(expected.copy(resource = "id/btn_7", text = "7"), "RESID")
    @Test fun sameResourceDifferentLabelRejected() = reject(expected.copy(text = "8"), "LABEL")
    @Test fun differentDescriptionRejected() = reject(expected.copy(description = "other"), "LABEL")
    @Test fun movedBoundsRejected() = reject(expected.copy(bounds = "[5,5][15,15]"), "BOUNDS")
    @Test fun disabledRejected() = reject(expected.copy(enabled = false), "DISABLED")
    @Test fun hiddenRejected() = reject(expected.copy(visible = false), "HIDDEN")
    @Test fun passwordRejected() = reject(expected.copy(password = true), "PASSWORD")
    @Test fun changedPackageRejected() = reject(expected.copy(pkg = "other"), "PKG")
    @Test fun changedClassRejected() = reject(expected.copy(cls = "EditText"), "CLASS")
    @Test fun changedCapabilityRejected() = reject(expected.copy(clickable = false), "CAPABILITY")

    @Test fun deadlineIncludesQueueAndDoesNotRestartOnMain() {
        var now = 0L
        val budget = UiOperationBudget(3000) { now }
        now = 900L
        budget.start()
        assertEquals(900L, budget.queueMs())
        assertEquals(2100L, budget.remaining())
        now = 3000L
        assertTrue(budget.expired())
        try { budget.check(); fail("Expected timeout") } catch (_: AgentUiTimeout) {}
    }

    @Test fun queuedRunningAndFinishedAreDistinct() {
        var now = 0L
        val budget = UiOperationBudget(3000) { now }
        assertEquals("NEVER_STARTED", budget.timeoutState())
        budget.start()
        assertEquals("RUNNING", budget.timeoutState())
        now = 50
        budget.end()
        assertEquals("FINISHED", budget.timeoutState())
        assertEquals(50L, budget.runMs())
    }

    @Test fun cancellationBeforeOrDuringReadStopsFurtherWork() {
        val before = UiOperationBudget(3000) { 0L }
        before.cancel()
        assertTrue(before.expired())
        val during = UiOperationBudget(3000) { 0L }
        during.start()
        during.cancel()
        try { during.check(); fail("Cancelled traversal must stop") } catch (_: AgentUiTimeout) {}
    }
}
