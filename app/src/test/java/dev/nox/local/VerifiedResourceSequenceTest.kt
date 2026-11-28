package dev.nox.local

import org.junit.Test
import org.junit.Assert.*

class VerifiedResourceSequenceTest {
    private fun state(keys: List<String>, seq: Long = 1, window: Int = 7) =
        ResourceSequence.of(keys).observation("task", "calc", window, seq)
    @Test fun sequenceCompletes() {
        val keys = listOf("1", "add", "2", "equal")
        val calls = mutableListOf<String>()
        val result = runVerifiedResources(state(keys), { 0L }, { false }, 100, 4) { o, k ->
            calls += k
            VerifiedDispatch.Observed(state(o.state.remaining.drop(1), o.key.sequence + 1))
        }
        assertEquals(LoopStatus.GOAL_VERIFIED, result.status)
        assertEquals(4, result.confirmed)
        assertEquals(keys, calls)
    }
    @Test fun unknownNeverReplays() {
        val calls = mutableListOf<String>()
        val result = runVerifiedResources(state(listOf("1","2")), { 0L }, { false }, 100, 4) { _, k ->
            calls += k; VerifiedDispatch.Unknown
        }
        assertEquals(LoopStatus.UNCERTAIN, result.status)
        assertEquals(listOf("1"), calls)
    }
    @Test fun windowChangeStops() {
        val result = runVerifiedResources(state(listOf("1","2")), { 0L }, { false }, 100, 4) { o, _ ->
            VerifiedDispatch.Observed(state(o.state.remaining.drop(1), o.key.sequence + 1, 8))
        }
        assertEquals(LoopStatus.SCOPE_CHANGED, result.status)
    }
    @Test fun inputCopied() {
        val source = mutableListOf("one", "two")
        val snap = state(source); source.clear()
        assertEquals(2, snap.state.remaining.size)
    }
    @Test fun signatureUnambiguous() {
        assertNotEquals(state(listOf("a", "bc")).key.signature, state(listOf("ab", "c")).key.signature)
    }
    @Test fun stepLimit() {
        val result = runVerifiedResources(state(listOf("1","2")), { 0L }, { false }, 100, 1) { o, _ ->
            VerifiedDispatch.Observed(state(o.state.remaining.drop(1), o.key.sequence + 1))
        }
        assertEquals(LoopStatus.STEP_LIMIT, result.status)
        assertEquals(1, result.confirmed)
    }
}
