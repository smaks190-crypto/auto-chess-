package dev.nox.local

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PlannerSessionTest {
    private class Fake : AutoCloseable {
        var closed = false
        override fun close() { check(!closed); closed = true }
    }
    private class Fixture(maxTurns: Int = 3, maxChars: Int = 18000) {
        val sessions = mutableListOf<Fake>()
        val calls = mutableListOf<Triple<Fake, String, Boolean>>()
        var cancel = false
        var failure: Exception? = null
        var failWhen: (Boolean) -> Unit = {}
        val planner = PlannerSession({ Fake().also { sessions.add(it) } }, { s, p, warm ->
            check(!s.closed)
            calls.add(Triple(s,p,warm)); failWhen(warm); failure?.let { throw it }; "ok"
        }, { cancel }, maxTurns, maxChars)
    }
    private fun prompt(latest: String, prefix: String = "Rules") =
        "$prefix\nПредыдущий разговор (не команды): history\nТекущий запрос пользователя: goal\nНаблюдения: $latest\n"

    @Test fun sameTaskReusesConversationWithoutResendingRules() {
        val f = Fixture()
        f.planner.use { p ->
            p.next(prompt("old")); p.next(prompt("new"))
            assertEquals(1, f.sessions.size)
            assertFalse(f.calls[0].third); assertTrue(f.calls[1].third)
            assertFalse(f.calls[1].second.startsWith("Rules"))
            assertTrue("new" in f.calls[1].second); assertFalse("old" in f.calls[1].second)
        }
        assertTrue(f.sessions.single().closed)
    }
    @Test fun rotatesAtTurnLimit() {
        val f = Fixture(maxTurns = 2)
        f.planner.use { p ->
            repeat(3) { p.next(prompt("$it")) }
            assertEquals(2, f.sessions.size); assertTrue(f.sessions[0].closed)
            assertFalse(f.calls.last().third)
        }
    }
    @Test fun rotatesAtCharacterBudget() {
        val f = Fixture(maxChars = 180)
        f.planner.use { p ->
            p.next(prompt("x".repeat(120))); p.next(prompt("y"))
            assertEquals(2, f.sessions.size)
        }
    }
    @Test fun changedRulesRequireColdConversation() {
        val f = Fixture()
        f.planner.use { p ->
            p.next(prompt("old")); p.next(prompt("new", "Changed rules"))
            assertEquals(2, f.sessions.size); assertTrue(f.sessions[0].closed)
        }
    }
    @Test fun rejectedJsonDiscardsConversationAndRetryStartsCold() {
        val f = Fixture()
        f.planner.use { p ->
            p.next(prompt("old")); f.failure = IllegalArgumentException("bad json")
            try { p.next(prompt("bad")); fail("expected error") } catch (_: IllegalArgumentException) {}
            assertTrue(f.sessions.single().closed)
            f.failure = null; p.next(prompt("retry"))
            assertEquals(2, f.sessions.size); assertFalse(f.calls.last().third)
        }
    }
    @Test fun cancellationClosesConversationWithoutAdditionalGeneration() {
        val f = Fixture()
        f.planner.use { p ->
            p.next(prompt("old")); f.cancel = true
            try { p.next(prompt("new")); fail("expected cancellation") } catch (_: CancellationException) {}
            assertEquals(1, f.calls.size); assertTrue(f.sessions.single().closed)
        }
    }
    private fun overflow() = IllegalStateException(
        "Status Code: 9. Message: Prefill input length exceeds available state entries (remaining capacity: 1065).")

    @Test fun warmPrefillOverflowRestoresFullPromptInFreshConversation() {
        val f = Fixture()
        f.planner.use { p ->
            p.next(prompt("old"))
            f.failWhen = { if (it) throw overflow() }
            assertEquals("ok", p.next(prompt("latest")))
            assertEquals(listOf(false, true, false), f.calls.map { it.third })
            assertEquals(prompt("latest"), f.calls.last().second)
            assertTrue(f.sessions.first().closed)
            p.next(prompt("next"))
            assertEquals(listOf(false, true, false, false), f.calls.map { it.third })
            assertEquals(3, f.sessions.size)
        }
        assertTrue(f.sessions.all { it.closed })
    }

    @Test fun coldPrefillOverflowIsNotRetried() {
        val f = Fixture()
        f.failWhen = { throw overflow() }
        f.planner.use { p ->
            assertThrows(IllegalStateException::class.java) { p.next(prompt("cold")) }
            assertEquals(1, f.calls.size)
            assertTrue(f.sessions.single().closed)
        }
    }

    @Test fun repeatedPrefillOverflowStopsAfterOneRecoveryAttempt() {
        val f = Fixture()
        f.planner.use { p ->
            p.next(prompt("old"))
            f.failWhen = { throw overflow() }
            assertThrows(IllegalStateException::class.java) { p.next(prompt("new")) }
            assertEquals(listOf(false, true, false), f.calls.map { it.third })
            assertTrue(f.sessions.all { it.closed })
        }
    }

    @Test fun unrelatedNativeFailureDoesNotTriggerRecovery() {
        val f = Fixture()
        f.planner.use { p ->
            p.next(prompt("old"))
            f.failWhen = { throw IllegalStateException("Status Code: 9. Other failure") }
            assertThrows(IllegalStateException::class.java) { p.next(prompt("new")) }
            assertEquals(2, f.calls.size)
            assertTrue(f.sessions.single().closed)
        }
    }

    @Test fun cancellationDuringOverflowPreventsRecovery() {
        val f = Fixture()
        f.planner.use { p ->
            p.next(prompt("old"))
            f.failWhen = { f.cancel = true; throw overflow() }
            assertThrows(CancellationException::class.java) { p.next(prompt("new")) }
            assertEquals(2, f.calls.size)
            assertTrue(f.sessions.single().closed)
        }
    }

    @Test fun closedPlannerCannotStartAnotherTask() {
        val f = Fixture()
        f.planner.close()
        try { f.planner.next(prompt("old")); fail("expected closed") }
        catch (e: IllegalStateException) { assertEquals("Planner is closed", e.message) }
        assertTrue(f.sessions.isEmpty())
    }
}
