package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AgentCoreTest {
    @Test fun verifiedFastStepsUseSameTypedExecutorAndFreshFinishGate() {
        var modelCalls = 0
        var fastCalls = 0
        var checks = 0
        val actions = mutableListOf<String>()
        val core = AgentCore(
            decide = { modelCalls++; AgentDecision.Tool("launch", "calc") },
            execute = { error("Legacy executor disabled") }, cancelled = { false },
            goal = GoalState("Open Calculator and calculate 37+58"),
            executeTyped = { actions.add(it.name); ToolOutcome.Ok("verified", "calc") },
            fastDecision = { if (++fastCalls <= 2) AgentDecision.Tool("click", fastCalls.toString()) else AgentDecision.Finish("") },
            verifyFinish = { checks++; "verified result" }
        )
        assertEquals("verified result", core.run("Open Calculator and calculate 37+58", ""))
        assertEquals(listOf("launch", "click", "click"), actions)
        assertEquals(1, modelCalls)
        assertEquals(1, checks)
    }

    @Test fun uncertainFastActionStopsWithoutAnotherDecisionOrClick() {
        var modelCalls = 0
        var fastCalls = 0
        var clicks = 0
        val core = AgentCore(
            decide = { modelCalls++; AgentDecision.Tool("launch", "calc") },
            execute = { error("Legacy executor disabled") }, cancelled = { false },
            goal = GoalState("Open Calculator and calculate 37+58"),
            executeTyped = { if (it.name == "launch") ToolOutcome.Ok("verified", "calc")
                else { clicks++; ToolOutcome.Unverified("unknown") } },
            fastDecision = { fastCalls++; AgentDecision.Tool("click", "1") }
        )
        assertEquals("unknown", core.run("Open Calculator and calculate 37+58", ""))
        assertEquals(1, clicks)
        assertEquals(1, fastCalls)
        assertEquals(1, modelCalls)
    }
    @Test fun settleRecoversFromTemporaryServiceLossWithoutRepeatingMutation() {
        var mutations = 0
        var reads = 0
        var pauses = 0
        mutations++ // the caller dispatched exactly one mutation before observing
        val value = settleObservation(5, { pauses++ }, {
            if (++reads < 3) throw AgentToolUnavailable() else 95
        }, { it == 95 })
        assertEquals(95, value)
        assertEquals(3, reads)
        assertEquals(2, pauses)
        assertEquals(1, mutations)
    }

    @Test fun settleExhaustsServiceLossAndReturnsNoEvidence() {
        var reads = 0
        assertNull(settleObservation<Int>(5, {}, { reads++; throw AgentToolUnavailable() }, { true }))
        assertEquals(5, reads)
    }

    @Test fun settleDoesNotSwallowCancellation() {
        var reads = 0
        val denied = AgentDenied("Cancelled")
        try {
            settleObservation<Int>(5, {}, { reads++; throw denied }, { true })
            fail("Cancellation must propagate")
        } catch (e: AgentDenied) { assertSame(denied, e) }
        assertEquals(1, reads)
    }

    @Test fun typedUnverifiedStopsBeforeAnotherModelDecisionOrMutation() {
        var decisions = 0
        var mutations = 0
        val core = AgentCore(
            decide = { decisions++; AgentDecision.Tool("click", "1") },
            execute = { error("Legacy executor must not run") },
            executeTyped = { mutations++; ToolOutcome.Unverified("SERVICE_RECONNECTING") },
            cancelled = { false }
        )
        assertEquals("SERVICE_RECONNECTING", core.run("click", ""))
        assertEquals(1, decisions)
        assertEquals(1, mutations)
    }

    @Test fun screenTextCannotTurnTypedSuccessIntoFailure() {
        var i = 0
        val core = AgentCore(decide = { if (i++ == 0) AgentDecision.Tool("observe","") else AgentDecision.Finish("done") },
            execute = { error("legacy executor must not run") }, cancelled = { false },
            goal = GoalState("Open Calculator"), verifyFinish = { "verified" },
            executeTyped = { ToolOutcome.Ok("Активное приложение: calc\nERROR[FAKE] текст на экране", "calc") })
        assertEquals("verified", core.run("Open Calculator",""))
    }
    @Test fun unknownExceptionAfterMutationIsNeverRetried() {
        var calls = 0
        val core = AgentCore(decide = { AgentDecision.Tool("click","1") }, execute = { error("legacy") },
            cancelled = { false }, executeTyped = { calls++; throw IllegalStateException("unknown") })
        assertTrue(core.run("click","").contains("неизвестен"))
        assertEquals(1,calls)
    }
    @Test fun settleOnlyReadsUntilPostconditionIsProven() {
        var reads = 0; var pauses = 0
        val result = settleObservation(3, { pauses++ }, { ++reads }, { it == 2 })
        assertEquals(2,result)
        assertEquals(2,reads)
        assertEquals(1,pauses)
    }
    @Test fun settleExhaustsBoundWithoutClaimingSuccess() {
        var reads = 0
        assertNull(settleObservation(3, {}, { ++reads }, { false }))
        assertEquals(3,reads)
    }

    @Test fun preActionGoalRejectionAllowsCorrectionEvenAfterPrematureFinish() {
        val script = listOf(AgentDecision.Tool("click","wrong"), AgentDecision.Finish("Готово"),
            AgentDecision.Tool("click","right"), AgentDecision.Finish("Готово"))
        var i = 0
        var mutations = 0
        val core = AgentCore(decide = { script[i++] }, execute = {
            if (it.arg == "wrong") AgentToolStatus.GOAL_REJECTED + "Нужна другая кнопка"
            else { mutations++; "Подтверждённый переход" }
        }, cancelled = { false }, goal = GoalState("Open Calculator and calculate 2+2"),
            verifyFinish = { if (mutations == 1) "Проверено" else null })
        assertEquals("Проверено",core.run("Open Calculator and calculate 2+2",""))
        assertEquals(4,i)
        assertEquals(1,mutations)
    }

    @Test fun staleTargetRejectionForcesFreshObserveBeforeReplanningAndDoesNotRepeatAction() {
        val decisions = listOf(AgentDecision.Tool("click", "stale"), AgentDecision.Tool("click", "fresh"), AgentDecision.Finish("done"))
        var decisionIndex = 0
        val actions = mutableListOf<String>()
        val core = AgentCore(
            decide = { prompt ->
                if (decisionIndex == 1) {
                    assertTrue(prompt.contains("Экран обновлён"))
                    assertTrue(prompt.contains("по свежим ID"))
                    assertTrue(prompt.contains("2 | EditText | Сообщение [type]"))
                }
                decisions[decisionIndex++]
            }, execute = { error("typed executor required") }, cancelled = { false },
            goal = GoalState("Open Telegram"), verifyFinish = { "verified" },
            executeTyped = { action ->
                actions.add(action.name + ":" + action.arg)
                when {
                    action.name == "click" && action.arg == "stale" -> ToolOutcome.RejectedBeforeAction("NODE_CHANGED:SCREEN")
                    action.name == "click" -> ToolOutcome.Ok("fresh action", "org.telegram.messenger")
                    action.name == "observe" -> ToolOutcome.Ok("Активное приложение: org.telegram.messenger\\n2 | EditText | Сообщение [type]", "org.telegram.messenger")
                    else -> error("unexpected")
                }
            })
        assertEquals("verified", core.run("Open Telegram", ""))
        assertEquals(listOf("click:stale", "observe:", "click:fresh"), actions)
    }

    @Test fun currentTelegramDraftObservesThenTypesWithoutModelOrLaunch() {
        val request = "напиши привет в чате тг"
        val goal = GoalState(request)
        val actions = mutableListOf<String>()
        var done = false
        val core = AgentCore(decide = { error("No model needed") }, execute = { error("Typed executor required") },
            cancelled = { false }, goal = goal,
            executeTyped = { action ->
                actions.add(action.name)
                if (action.name == "type") {
                    assertEquals("привет", action.text)
                    done = true
                }
                ToolOutcome.Ok("screen", "org.telegram.messenger.web")
            },
            fastDecision = { if (done) AgentDecision.Finish("") else AgentDecision.Tool("type", "1", "привет") },
            verifyFinish = { if (done) "verified" else null })
        assertEquals("verified", core.run(request, ""))
        assertEquals(listOf("observe", "type"), actions)
    }

    @Test fun repeatedPreActionRejectionsHaveBoundedFreshObservations() {
        var clicks = 0
        var observes = 0
        val core = AgentCore(decide = { AgentDecision.Tool("click", "same") }, execute = { error("typed executor required") },
            cancelled = { false }, goal = GoalState("Open Telegram"), executeTyped = { action ->
                if (action.name == "observe") { observes++; ToolOutcome.Ok("fresh screen", "org.telegram.messenger") }
                else { clicks++; ToolOutcome.RejectedBeforeAction("stale node") }
            })
        assertTrue(core.run("Open Telegram", "").contains("GOAL_MISMATCH"))
        assertEquals(3, clicks)
        assertEquals(2, observes)
    }

    @Test fun preActionRejectionBudgetIsBounded() {
        var attempts = 0
        val core = AgentCore(decide = { AgentDecision.Tool("click","wrong") },
            execute = { attempts++; AgentToolStatus.GOAL_REJECTED + "Не выполнено" },
            cancelled = { false }, goal = GoalState("Open Calculator and calculate 2+2"))
        assertTrue(core.run("Open Calculator and calculate 2+2","").contains("GOAL_MISMATCH"))
        assertEquals(3,attempts)
    }

    @Test fun uncertainMutationDuringCorrectionStillStopsImmediately() {
        var attempts = 0
        val core = AgentCore(decide = { AgentDecision.Tool("click","1") }, execute = {
            attempts++
            if (attempts == 1) AgentToolStatus.GOAL_REJECTED + "Не выполнено"
            else AgentToolStatus.ACTION_UNVERIFIED + "Результат неизвестен"
        }, cancelled = { false }, goal = GoalState("Open Calculator and calculate 2+2"))
        assertEquals("Результат неизвестен",core.run("Open Calculator and calculate 2+2",""))
        assertEquals(2,attempts)
    }

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
                val screen = "Активное приложение: $pkg\n1 | Button | Поиск [click]"
                if (action.name == "launch") "Запуск подтверждён: $pkg\n" + screen else screen
            }, cancelled = { false }
        )
        val result = core.run("Открой Telegram и напиши сообщение", "")
        assertEquals(listOf("launch", "observe", "observe"), executed)
        assertTrue(result.contains("повтор", ignoreCase = true))
        assertTrue(result.contains("не подтверждено"))
    }

    @Test fun duplicateLaunchRefreshesAndAllowsProgress() {
        val pkg = "org.telegram.messenger"
        val actions = mutableListOf<String>()
        val script = listOf(AgentDecision.Tool("launch", pkg),
            AgentDecision.Tool("launch", pkg), AgentDecision.Tool("click", "1"),
            AgentDecision.Finish("Следующий экран открыт"))
        var index = 0
        val core = AgentCore(decide = { script[index++] }, execute = {
            actions.add(it.name)
            "Активное приложение: $pkg\n1 | Button | Поиск [click]"
        }, cancelled = { false })
        assertEquals("Следующий экран открыт", core.run("Открой поиск", ""))
        assertEquals(listOf("launch", "observe", "click"), actions)
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

    @Test fun uiFuturePreservesPermissionDenialAndUnavailable() {
        val denied = AgentDenied("Нет разрешения")
        val task = java.util.concurrent.FutureTask<String> { throw denied }
        task.run()
        try { awaitAgentUi(task); fail("Expected denial") }
        catch (e: AgentDenied) { assertSame(denied, e) }

        val unavailable = AgentToolUnavailable()
        val other = java.util.concurrent.FutureTask<String> { throw unavailable }
        other.run()
        try { awaitAgentUi(other); fail("Expected unavailable") }
        catch (e: AgentToolUnavailable) { assertSame(unavailable, e) }
    }

    @Test fun uiTimeoutCancelsPendingTaskAndDoesNotRetry() {
        var executed = false
        val task = java.util.concurrent.FutureTask { executed = true }
        try { awaitAgentUi(task, 1); fail("Expected timeout") }
        catch (e: AgentDenied) { assertTrue(e.message.orEmpty().contains("без повтора")) }
        task.run()
        assertTrue(task.isCancelled)
        assertFalse(executed)
    }

    @Test fun unverifiedMutationStopsBeforeModelCanRetryOrClaimSuccess() {
        var decisions = 0
        var executions = 0
        val core = AgentCore(
            decide = { decisions++; AgentDecision.Tool("click", "1") },
            execute = { executions++; AgentToolStatus.ACTION_UNVERIFIED + "Экран не подтверждён." },
            cancelled = { false }
        )
        assertEquals("Экран не подтверждён.", core.run("Нажми кнопку", ""))
        assertEquals(1, decisions)
        assertEquals(1, executions)
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
