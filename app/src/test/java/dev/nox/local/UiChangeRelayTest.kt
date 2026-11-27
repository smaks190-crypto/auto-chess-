package dev.nox.local

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

class UiChangeRelayTest {
    @Test fun filtersPackageAndWindow() {
        val r = UiChangeRelay()
        val s = requireNotNull(r.begin("calc", 7))
        val marker = s.mark()
        r.publish("other", 7)
        r.publish("calc", 8)
        r.publish(null, 7)
        assertEquals(marker, s.mark())
        r.publish("calc", 7)
        assertEquals(marker + 1L, s.mark())
        r.end(s)
    }

    @Test fun eventBeforeWaitIsNotLost() {
        val r = UiChangeRelay()
        val s = requireNotNull(r.begin("calc", 7))
        r.publish("calc", 7)
        val marker = s.mark()
        assertEquals(marker, s.awaitAfter(marker, 0) {})
        r.publish("calc", 7)
        assertEquals(marker + 1L, s.awaitAfter(marker, 0) {})
        r.end(s)
    }

    @Test fun waitingWorkerWakesOnMatchingEvent() {
        val r = UiChangeRelay()
        val s = requireNotNull(r.begin("calc", 7))
        val marker = s.mark()
        val ready = CountDownLatch(1)
        val task = FutureTask<Long> { s.awaitAfter(marker, 2000) { ready.countDown() } }
        val worker = Thread(task).apply { start() }
        try {
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            r.publish("calc", 7)
            assertEquals(marker + 1L, task.get(1, TimeUnit.SECONDS))
        } finally { r.end(s); worker.join(1000) }
    }

    @Test fun noEventReturnsUnchangedMarkerForFallbackRead() {
        val s = UiChangeSignal("calc", 7)
        assertEquals(0L, s.awaitAfter(0, 20) {})
    }

    @Test fun cancellationPropagatesInsteadOfReturningEvidence() {
        val s = UiChangeSignal("calc", 7)
        val cancel = IllegalStateException("cancel")
        try { s.awaitAfter(0, 500) { throw cancel }; fail("expected cancellation") }
        catch (e: IllegalStateException) { assertSame(cancel, e) }
    }

    @Test fun oldSubscriptionCannotRemoveNewOne() {
        val r = UiChangeRelay()
        val old = requireNotNull(r.begin("calc", 7))
        assertNull(r.begin("calc", 7))
        r.end(old)
        val next = requireNotNull(r.begin("calc", 7))
        r.end(old)
        r.publish("calc", 7)
        assertEquals(0L, old.mark())
        assertEquals(1L, next.mark())
        r.end(next)
    }
}
