package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AgentActionGateTest {
    @Test fun narrationCompletesBeforeAction() {
        val events = mutableListOf<String>()
        val gate = AgentActionGate({ false }, { events.add(it) })
        gate.before("Открываю Telegram")
        events.add("launch")
        assertEquals(listOf("Открываю Telegram", "launch"), events)
    }

    @Test fun onlyKnownSafeActionsSkipExplicitConfirmation() {
        val events = mutableListOf<String>()
        val gate = AgentActionGate({ false }, { events.add("visible") })
        gate.before("search", requiresConfirmation = false) { events.add("confirm") }
        assertEquals(listOf("visible"), events)
        events.clear()
        gate.before("unknown", requiresConfirmation = true) { events.add("confirm") }
        assertEquals(listOf("visible", "confirm", "visible"), events)
    }
    @Test fun cancellationDuringApprovalBlocksMutation() {
        var stopped = false
        val gate = AgentActionGate({ stopped }, {})
        assertThrows(AgentDenied::class.java) {
            gate.before("unknown", requiresConfirmation = true) { stopped = true }
        }
    }
    @Test fun cancellationDuringNarrationBlocksAction() {
        var stopped = false
        val gate = AgentActionGate({ stopped }, { stopped = true })
        assertThrows(AgentDenied::class.java) { gate.before("Открываю Telegram") }
    }

    @Test fun cancelledTaskNeverNarratesOrActs() {
        var calls = 0
        val gate = AgentActionGate({ true }, { calls++ })
        assertThrows(AgentDenied::class.java) { gate.before("Открываю Telegram") }
        assertEquals(0, calls)
    }

    @Test fun stopPhraseDoesNotMatchQuotedOrNegatedRequest() {
        assertTrue(VoiceLoopPolicy.isCancelTaskPhrase("Нокс, стоп!"))
        assertTrue(VoiceLoopPolicy.isCancelTaskPhrase("остановись"))
        assertTrue(VoiceLoopPolicy.isCancelTaskPhrase("отмени задачу"))
        assertFalse(VoiceLoopPolicy.isCancelTaskPhrase("не останавливайся"))
        assertFalse(VoiceLoopPolicy.isCancelTaskPhrase("напиши слово стоп"))
        assertFalse(VoiceLoopPolicy.isCancelTaskPhrase("стоп кадр"))
    }
}
