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
        assertEquals("Telegram открыт", core.run("Открой телеграм", ""))
        assertEquals(listOf("launch"), calls)
    }

    @Test fun repairsTwoMalformedDecisionsBeforeAnyExecution() {
        val prompts = mutableListOf<String>()
        var executions = 0
        val core = AgentCore(
            decide = { prompt ->
                prompts.add(prompt)
                if (prompts.size <= 2) throw AgentFormatException("Unterminated object")
                AgentDecision.Tool("apps", "Telegram")
            },
            execute = { executions++; "Telegram = org.telegram.messenger" },
            cancelled = { false }, maxSteps = 1
        )
        core.run("Найди Telegram", "")
        assertEquals(3, prompts.size)
        assertEquals(1, executions)
        assertTrue(prompts[1].contains("Попытка исправления 1"))
        assertTrue(prompts[2].contains("Попытка исправления 2"))
    }

    @Test fun invalidDecisionsNeverExecuteAndStopAfterThreeAttempts() {
        var generations = 0
        var executions = 0
        val core = AgentCore(
            decide = { generations++; throw AgentFormatException("Invalid JSON") },
            execute = { executions++; "unreachable" }, cancelled = { false }
        )
        assertTrue(core.run("Открой", "").contains("трёх попыток"))
        assertEquals(3, generations)
        assertEquals(0, executions)
    }

    @Test fun ordinaryModelFailureIsNotTreatedAsFormatError() {
        var generations = 0
        val core = AgentCore(
            decide = { generations++; throw IllegalStateException("Model unavailable") },
            execute = { "unreachable" }, cancelled = { false }
        )
        assertTrue(core.run("Открой", "").contains("Model unavailable"))
        assertEquals(1, generations)
    }

    @Test fun accessibilityFailureIsReportedWithoutInventingCapabilities() {
        var executions = 0
        val core = AgentCore(
            decide = { AgentDecision.Tool("observe", "") },
            execute = { executions++; throw AgentToolUnavailable() },
            cancelled = { false }
        )
        val result = core.run("Прочитай экран", "")
        assertTrue(result.contains("специальных возможностях"))
        assertEquals(1, executions)
    }

    @Test fun unverifiedLaunchCannotBeDeclaredSuccessfulByModel() {
        var decisions = 0
        val core = AgentCore(
            decide = { decisions++; if (decisions == 1) AgentDecision.Tool("launch", "org.telegram.messenger")
                else AgentDecision.Finish("Я точно открыл Telegram") },
            execute = { AgentToolStatus.LAUNCH_UNVERIFIED + "Запрос на открытие Telegram отправлен, экран не проверен." },
            cancelled = { false }
        )
        assertEquals("Запрос на открытие Telegram отправлен, экран не проверен.", core.run("Открой", ""))
        assertEquals(1, decisions)
    }

    @Test fun backgroundLaunchWaitsForUserTapInsteadOfFalseSuccess() {
        val core = AgentCore(
            decide = { AgentDecision.Tool("launch", "org.telegram.messenger") },
            execute = { AgentToolStatus.LAUNCH_NEEDS_TAP + "Нажми уведомление N.O.X." },
            cancelled = { false }
        )
        assertEquals("Нажми уведомление N.O.X.", core.run("Открой", ""))
    }

    @Test fun repeatedToolFailuresStopAfterTwoExecutions() {
        var executions = 0
        val core = AgentCore(
            decide = { AgentDecision.Tool("observe", "") },
            execute = { executions++; throw IllegalStateException("Window blocked") },
            cancelled = { false }
        )
        assertTrue(core.run("Посмотри экран", "").contains("Повторная ошибка"))
        assertEquals(2, executions)
    }

    @Test fun failedToolDoesNotBecomeModelRefusal() {
        var decisions = 0
        val core = AgentCore(
            decide = {
                decisions++
                if (decisions == 1) AgentDecision.Tool("launch", "com.example")
                else AgentDecision.Finish("У меня нет инструментов")
            },
            execute = { throw IllegalStateException("No launch activity") },
            cancelled = { false }
        )
        val result = core.run("Открой", "")
        assertTrue(result.contains("TOOL_FAILED"))
        assertFalse(result.contains("нет инструментов"))
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
