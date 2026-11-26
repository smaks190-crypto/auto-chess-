package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AgentCoreTest {
    @Test fun modelChoosesNextStepFromToolResult() {
        val calls = mutableListOf<String>()
        val core = AgentCore(
            decide = { prompt ->
                if (prompt.contains("виден Telegram")) AgentDecision.Finish("Telegram открыт")
                else AgentDecision.Tool("launch", "org.telegram.messenger")
            },
            execute = { action -> calls.add(action.name); "виден Telegram" },
            cancelled = { false }
        )
        assertTrue(core.run("Открой телеграм", "").contains("виден Telegram"))
        assertEquals(listOf("launch"), calls)
    }
    @Test fun stopsAfterBoundedSteps() {
        var count = 0
        val core = AgentCore(
            decide = { AgentDecision.Tool("observe", "") },
            execute = { count++; "Без изменений" },
            cancelled = { false }, maxSteps = 3
        )
        assertTrue(core.run("Посмотри", "").contains("лимит", ignoreCase = true))
        assertEquals(3, count)
    }
    @Test fun denialStopsBeforeAnotherAction() {
        var count = 0
        val core = AgentCore(
            decide = { AgentDecision.Tool("click", "1") },
            execute = { count++; throw AgentDenied() },
            cancelled = { false }
        )
        assertTrue(core.run("Нажми", "").contains("отмен", ignoreCase = true))
        assertEquals(1, count)
    }
    @Test fun cancellationPreventsExecution() {
        var count = 0
        val core = AgentCore(
            decide = { AgentDecision.Tool("launch", "com.example") },
            execute = { count++; "ok" },
            cancelled = { true }
        )
        assertTrue(core.run("Открой", "").contains("отмен", ignoreCase = true))
        assertEquals(0, count)
    }
}
