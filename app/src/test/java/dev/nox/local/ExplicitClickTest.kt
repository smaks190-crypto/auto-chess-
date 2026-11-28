package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class ExplicitClickTest {
    private val pkg = "sample.app"
    private val button = GoalNode("sample.app:id/menu", "Меню", "4", description = "Меню", kind = "Button", clickable = true)
    @Test fun explicitTargetMustBeUniqueAndBoundToRequestedApp() {
        val goal = GoalState("Нокс, открой Пример и нажми кнопку Меню")
        assertTrue(goal.requiresNoModel)
        assertEquals("Пример", goal.clickApp)
        assertEquals(pkg, goal.clickLaunchPackage(mapOf(pkg to "Пример", "other.app" to "Другой")))
        assertNull(goal.clickLaunchPackage(mapOf(pkg to "Пример", "other.app" to "Пример")))
        fun screen(vararg nodes: GoalNode, app: String = pkg) = GoalObservation(goal.sequence(), app, nodes.toList())
        assertNull(goal.explicitClickNode(screen(button)))
        goal.launched(pkg, "Пример")
        assertEquals("4", goal.explicitClickNode(screen(button))?.id)
        assertNull(goal.explicitClickNode(screen(button, button.copy(id = "5"))))
        assertNull(goal.explicitClickNode(screen(button, app = "other.app")))
        assertNull(goal.explicitClickNode(screen(button.copy(clickable = false))))
        assertNull(goal.explicitClickNode(screen(button.copy(description = "Меню другое", label = "Меню другое"))))
        val before = screen(button)
        assertNull(goal.finish(before))
        goal.mutation()
        val after = screen()
        assertFalse(goal.clickObserved(before, after.copy(seq = before.seq), button))
        assertTrue(goal.clickObserved(before, after, button))
        assertTrue(goal.finish(screen())!!.contains("Меню"))
        assertNull(goal.finish(screen(app = "other.app")))
    }
    @Test fun unambiguousCurrentAppRequestDoesNotLaunchOrInventCompoundGoal() {
        val goal = GoalState("Нажми кнопку «Поиск»")
        assertTrue(goal.explicitClick)
        assertNull(goal.clickApp)
        assertFalse(goal.allowsLaunch(pkg))
        assertFalse(GoalState("Как нажать кнопку Поиск?").explicitClick)
        assertFalse(GoalState("Не нажимай кнопку Поиск").explicitClick)
        assertFalse(GoalState("Нажми Поиск и отправь сообщение").explicitClick)
    }
    @Test fun exactClickRunsWithoutPlannerAndChecksFreshResult() {
        val request = "Нажми кнопку Меню"
        val goal = GoalState(request)
        val steps = mutableListOf<String>()
        var observed: GoalObservation? = null
        val core = AgentCore(decide = { error("No model required") }, execute = { error("typed executor required") },
            cancelled = { false }, goal = goal, executeTyped = { action ->
                steps.add(action.name)
                when (action.name) {
                    "observe" -> { observed = GoalObservation(goal.sequence(), pkg, listOf(button)); ToolOutcome.Ok("screen", pkg) }
                    "click" -> {
                        assertEquals("4", action.arg)
                        val before = requireNotNull(observed)
                        goal.mutation()
                        val after = GoalObservation(goal.sequence(), pkg, emptyList())
                        assertTrue(goal.clickObserved(before, after, button))
                        observed = after
                        ToolOutcome.Ok("changed", pkg)
                    }
                    else -> error("Unexpected action")
                }
            }, fastDecision = {
                val screen = requireNotNull(observed)
                if (goal.finish(screen) != null) AgentDecision.Finish("")
                else goal.explicitClickNode(screen)?.let { AgentDecision.Tool("click", it.id) }
            }, verifyFinish = { goal.finish(GoalObservation(goal.sequence(), pkg, emptyList())) })
        assertTrue(core.run(request, "").contains("Меню"))
        assertEquals(listOf("observe", "click"), steps)
    }
}
