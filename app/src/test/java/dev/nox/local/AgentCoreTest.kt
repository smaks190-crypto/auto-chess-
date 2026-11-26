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

    @Test fun fullTelegramJourneyContinuesBeyondAppLaunch() {
        // A simulated model run: these are tool contracts, not a real Telegram session.
        // The last step prepares a draft; it does NOT send a message to a real person.
        val script = listOf(
            AgentDecision.Tool("apps", "Telegram"),
            AgentDecision.Tool("launch", "org.telegram.messenger"),
            AgentDecision.Tool("observe", ""),
            AgentDecision.Tool("click", "2"),
            AgentDecision.Tool("observe", ""),
            AgentDecision.Tool("type", "4", "Привет"),
            AgentDecision.Tool("observe", ""),
            AgentDecision.Finish("Черновик готов, отправку ещё не выполнял.")
        )
        var position = 0
        val executed = mutableListOf<String>()
        val core = AgentCore(
            decide = { script[position++] },
            execute = { action ->
                executed.add(action.name)
                when (action.name) {
                    "apps" -> "Telegram = org.telegram.messenger"
                    "launch", "observe" -> "Активное приложение: org.telegram.messenger\n2 | Button | Поиск [click]\n4 | EditText | Сообщение [type]"
                    "click" -> "Android принял действие. Активное приложение: org.telegram.messenger"
                    "type" -> "Android принял действие. Черновик присутствует."
                    else -> error("Непредвиденный инструмент")
                }
            },
            cancelled = { false }
        )
        assertEquals("Черновик готов, отправку ещё не выполнял.", core.run("Напиши Александру привет", ""))
        assertEquals(listOf("apps", "launch", "observe", "click", "observe", "type", "observe"), executed)
    }

    @Test fun uncertainLaunchWaitsForVerifiedScreenThenResumes() {
        var decisions = 0
        var observations = 0
        val executed = mutableListOf<String>()
        val core = AgentCore(
            decide = {
                decisions++
                when (decisions) {
                    1 -> AgentDecision.Tool("launch", "org.telegram.messenger")
                    2 -> AgentDecision.Tool("click", "2")
                    else -> AgentDecision.Finish("Открыт нужный экран")
                }
            },
            execute = { action ->
                executed.add(action.name)
                when (action.name) {
                    "launch" -> AgentToolStatus.LAUNCH_RECHECK + "Ожидается проверка."
                    "observe" -> {
                        observations++
                        if (observations == 1) "Активное приложение: dev.other.app"
                        else "Активное приложение: org.telegram.messenger\n2 | Button | Поиск [click]"
                    }
                    "click" -> "Android принял действие"
                    else -> error("Unexpected")
                }
            },
            cancelled = { false }
        )
        assertEquals("Открыт нужный экран", core.run("Открой Telegram и нажми поиск", ""))
        assertEquals(listOf("launch", "observe", "observe", "click"), executed)
        assertEquals(3, decisions)
    }

    @Test fun uncertainLaunchNeverClicksAnotherApp() {
        var decisions = 0
        var clicks = 0
        var observes = 0
        val core = AgentCore(
            decide = {
                decisions++
                AgentDecision.Tool("launch", "org.telegram.messenger")
            },
            execute = { action ->
                when (action.name) {
                    "launch" -> AgentToolStatus.LAUNCH_RECHECK + "Ожидается проверка."
                    "observe" -> {
                        observes++
                        "Активное приложение: dev.other.app\n1 | Button | Похожий элемент [click]"
                    }
                    "click", "type" -> { clicks++; error("Нельзя управлять чужим окном") }
                    else -> error("Unexpected")
                }
            },
            cancelled = { false }
        )
        val result = core.run("Открой Telegram и напиши сообщение", "")
        assertTrue(result.contains("не подтверждён"))
        assertEquals(1, decisions)
        assertEquals(5, observes)
        assertEquals(0, clicks)
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
            execute = { count++; "Наблюдение $count" },
            cancelled = { false }, maxSteps = 3
        )
        assertTrue(core.run("Посмотри", "").contains("лимит", ignoreCase = true))
        assertEquals(3, count)
    }

    @Test fun alreadyVerifiedAppCannotBeLaunchedRepeatedly() {
        val pkg = "org.telegram.messenger"
        val executed = mutableListOf<String>()
        val core = AgentCore(
            decide = { AgentDecision.Tool("launch", pkg) },
            execute = { action ->
                executed.add(action.name)
                "Запуск подтверждён: $pkg\nАктивное приложение: $pkg\n1 | Button | Поиск [click]"
            }, cancelled = { false }
        )
        val result = core.run("Открой Telegram и напиши сообщение", "")
        assertEquals(listOf("launch"), executed)
        assertTrue(result.contains("повтор", ignoreCase = true))
        assertTrue(result.contains("не подтверждено"))
    }

    @Test fun repeatedReadOnlyCycleStopsBeforeStepLimit() {
        var decisions = 0
        val core = AgentCore(
            decide = { if (decisions++ % 2 == 0) AgentDecision.Tool("apps", "Telegram")
                else AgentDecision.Tool("observe", "") },
            execute = { if (it.name == "apps") "Telegram = org.telegram.messenger"
                else "Активное приложение: org.telegram.messenger" },
            cancelled = { false }
        )
        assertTrue(core.run("Найди чат", "").contains("без прогресса"))
        assertEquals(5, decisions)
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
