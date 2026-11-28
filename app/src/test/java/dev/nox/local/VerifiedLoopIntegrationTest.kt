package dev.nox.local

import org.junit.Test
import org.junit.Assert.*

class VerifiedLoopIntegrationTest {
    private val request = "Open Calculator and calculate 37+58"
    @Test fun admittedLoopDoesNotRunLegacyDecisionAgain() {
        var calls = 0
        var batches = 0
        val core = AgentCore(decide = { calls++; AgentDecision.Tool("observe", "") },
            execute = { error("legacy") }, cancelled = { false }, goal = GoalState(request),
            executeTyped = { ToolOutcome.Ok("verified", "com.sec.android.app.popupcalculator") },
            fastDecision = { error("batch must own remaining actions") },
            runVerifiedGoal = { budget -> assertEquals(12, budget); batches++; "batch completed" })
        assertEquals("batch completed", core.run(request, ""))
        assertEquals(1, calls)
        assertEquals(1, batches)
    }
    @Test fun notAdmittedUsesExistingPath() {
        var batches = 0
        val actions = mutableListOf<String>()
        val core = AgentCore(decide = { AgentDecision.Tool("observe", "") },
            execute = { error("legacy") }, cancelled = { false }, goal = GoalState(request),
            executeTyped = { actions.add(it.name); ToolOutcome.Ok("verified", "calc") },
            runVerifiedGoal = { batches++; null },
            fastDecision = { AgentDecision.Finish("") }, verifyFinish = { "verified" })
        assertEquals("verified", core.run(request, ""))
        assertEquals(listOf("observe"), actions)
        assertEquals(1, batches)
    }
    @Test fun uncertainLoopDoesNotFallBack() {
        var calls = 0
        val core = AgentCore(decide = { AgentDecision.Tool("observe", "") },
            execute = { error("legacy") }, cancelled = { false }, goal = GoalState(request),
            executeTyped = { calls++; ToolOutcome.Ok("verified", "calc") },
            runVerifiedGoal = { error("unknown mutation") },
            fastDecision = { error("must not replay") })
        assertTrue(core.run(request, "").contains("Повторять не буду"))
        assertEquals(1, calls)
    }
    @Test fun unrelatedGoalNeverEntersLoop() {
        val core = AgentCore(decide = { error("no model") },
            execute = { error("legacy") }, cancelled = { false }, goal = GoalState("Открой Telegram"),
            executeTyped = { ToolOutcome.Ok("no match") },
            fastDecision = { null }, runVerifiedGoal = { error("wrong goal") })
        assertTrue(core.run("Открой Telegram", "").contains("однозначно"))
    }
}
