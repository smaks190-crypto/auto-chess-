package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class GoalStateTest {
    private val pkg = "com.sec.android.app.popupcalculator"
    private val key = "$pkg:id/calc_keypad_btn_"
    private fun screen(g: GoalState, text: String, packageName: String = pkg) =
        GoalObservation(g.sequence(), packageName, listOf(
            GoalNode("$pkg:id/calc_edt_formula", "Поле ввода в калькуляторе " + text),
            GoalNode(key + "04", "4 / 4"),
            GoalNode("$pkg:id/calc_handle_btn_converter", "Конвертация единиц")
        ))
    @Test fun keyFourIsNotResultAndWrongButtonBlocked() {
        val g = GoalState("Open Calculator and calculate 2+2 using buttons")
        assertNull(g.finish(screen(g,"0")))
        assertNotNull(g.reject(screen(g,"2"),"click","$pkg:id/calc_handle_btn_converter"))
        assertNull(g.reject(screen(g,"2"),"click",key + "add"))
        assertNotNull(g.reject(screen(g,"2"),"click",key + "02"))
    }
    @Test fun resultMustFollowObservedEqualsAndBeFresh() {
        val g = GoalState("Открой калькулятор и сложи два плюс два")
        assertTrue(g.arithmetic)
        assertNull(g.finish(screen(g,"4")))
        val before = screen(g,"2+2")
        g.mutation()
        g.afterClick(before,key + "equal",screen(g,"4"))
        assertNull(g.finish(before))
        assertEquals("В калькуляторе подтверждено: 2+2 = 4.",g.finish(screen(g,"4")))
        assertNull(g.finish(screen(g,"4","other.app")))
        assertNull(g.finish(screen(g,"5")))
        g.mutation()
        assertNull(g.finish(screen(g,"4")))
    }
    @Test fun wrongEqualsDoesNotConfirmEvenIfResultExistsAsKey() {
        val g = GoalState("Open Calculator and calculate 37+58")
        val before = screen(g,"37+58")
        g.mutation()
        g.afterClick(before,key + "equal",screen(g,"94"))
        assertNull(g.finish(screen(g,"95")))
    }
    @Test fun ambiguousExpressionIsNotInterpretedAsSimpleCalculation() {
        assertFalse(GoalState("Open Calculator and calculate 2+2+2").arithmetic)
        assertFalse(GoalState("Open Calculator and calculate 2.5+2").arithmetic)
        assertFalse(GoalState("Open Calculator and calculate 2+2 and 7").arithmetic)
    }
    @Test fun openRequiresMatchingActualPackageAndRequestedLabel() {
        val g = GoalState("Open Calculator")
        g.launched(pkg,"Калькулятор")
        assertNotNull(g.finish(screen(g,"0")))
        assertNull(g.finish(screen(g,"0","other.app")))
        val other = GoalState("Open Telegram")
        other.launched(pkg,"Калькулятор")
        assertNull(other.finish(screen(other,"0")))
    }
    @Test fun finishGateIgnoresModelClaimsAndStopsAfterTwoRejections() {
        var attempts = 0
        val core = AgentCore(decide = { AgentDecision.Finish("Готово, ответ 4") },
            execute = { error("No mutation allowed") }, cancelled = { false },
            goal = GoalState("Open Calculator and calculate 2+2"),
            verifyFinish = { attempts++; null })
        assertTrue(core.run("Open Calculator and calculate 2+2","").contains("UNVERIFIED_FINISH"))
        assertEquals(2,attempts)
    }
}
