package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class GoalStateTest {
    @Test fun directOpenRequiresExactUniqueAppAndNoFollowUp() {
        val goal = GoalState("Нокс, открой калькулятор")
        assertEquals("калькулятор", goal.directOpenTarget)
        assertEquals("com.sec.android.app.popupcalculator",
            goal.directOpenPackage(mapOf("com.sec.android.app.popupcalculator" to "Калькулятор", "other.app" to "Калькулятор плюс")))
        assertNull(goal.directOpenPackage(mapOf("one.app" to "Калькулятор", "two.app" to "Калькулятор")))
        assertNull(goal.directOpenPackage(mapOf("other.app" to "Калькулятор плюс")))
        assertNull(GoalState("Открой Telegram и напиши привет").directOpenTarget)
        assertNull(GoalState("Как открыть Telegram?").directOpenTarget)
        assertNull(GoalState("Не открывай Telegram").directOpenTarget)
    }

    private val pkg = "com.sec.android.app.popupcalculator"
    private val key = "$pkg:id/calc_keypad_btn_"
    private fun screen(g: GoalState, text: String, packageName: String = pkg) =
        GoalObservation(g.sequence(), packageName, listOf(
            GoalNode("$pkg:id/calc_edt_formula", "Поле ввода в калькуляторе " + text),
            GoalNode(key + "04", "4 / 4"),
            GoalNode("$pkg:id/calc_handle_btn_converter", "Конвертация единиц")
        ))
    @Test fun actualSamsungResultSuffixVerifiesOnlyAfterEquals() {
        val g = GoalState("Open Calculator and calculate 37+58")
        assertNull(g.finish(screen(g, "95 Результат вычисления")))
        val before = screen(g, "37 Плюс 58")
        g.mutation()
        assertFalse(g.afterClick(before, key + "equal", screen(g, "94 Результат вычисления")))
        assertFalse(g.afterClick(before, key + "equal", screen(g, "95 Результат вычисления мусор")))
        assertTrue(g.afterClick(before, key + "equal", screen(g, "95 Результат вычисления")))
        assertEquals("В калькуляторе подтверждено: 37+58 = 95.", g.finish(screen(g, "95 Результат вычисления")))
    }

    @Test fun negativeResultSuffixAndEnglishResult() {
        val g = GoalState("Open Calculator and calculate 37-58")
        val before = screen(g, "37-58")
        g.mutation()
        assertTrue(g.afterClick(before, key + "equal", screen(g, "−21 Результат вычисления")))
        assertNotNull(g.finish(screen(g, "-21 Calculation result")))
    }

    @Test fun exactPlanIsOnlyRemainingKeysAndNeverImplicitClear() {
        val g = GoalState("Open Calculator and calculate 37+58")
        assertEquals(listOf("03", "07", "add", "05", "08", "equal").map { key + it },
            g.approvedSequence(screen(g, "")))
        assertEquals(listOf("05", "08", "equal").map { key + it },
            g.approvedSequence(screen(g, "37 Плюс")))
        assertNull(g.approvedSequence(screen(g, "95 Результат вычисления")))
        assertNull(g.approvedSequence(screen(g, "", "other.app")))
        val ambiguous = GoalState("Open Calculator and calculate 37+58 and send it")
        assertNull(ambiguous.approvedSequence(screen(ambiguous, "")))
    }

    @Test fun samsungSpokenPlusIsVerifiedAndNextKeyIsFive() {
        val g = GoalState("Open Calculator and calculate 37+58 using the buttons")
        val before = screen(g, "37")
        g.mutation()
        val after = screen(g, "37 Плюс ")
        assertTrue(g.afterClick(before, key + "add", after))
        assertEquals(key + "05", g.fastResource(after))
        g.mutation()
        assertTrue(g.afterClick(after, key + "05", screen(g, "37 Плюс 5")))
    }

    @Test fun localizedMinusAndEnglishPlusAreVerified() {
        val g = GoalState("Open Calculator and calculate 37-58")
        val before = screen(g, "37")
        g.mutation()
        assertTrue(g.afterClick(before, key + "sub", screen(g, "37 МИНУС ")))
        val h = GoalState("Open Calculator and calculate 37+58")
        val b = screen(h, "37")
        h.mutation()
        assertTrue(h.afterClick(b, key + "add", screen(h, "37 Plus ")))
    }

    @Test fun unknownWordsAndUnsupportedOperatorsRemainRejected() {
        val g = GoalState("Open Calculator and calculate 37+58")
        for (bad in listOf("37 Плюсик", "37 Умножить", "37 plus error", "37 Плюс Плюс")) {
            val before = screen(g, "37")
            g.mutation()
            assertFalse(g.afterClick(before, key + "add", screen(g, bad)))
        }
    }

    @Test fun deterministicPathRequiresSingleExplicitTaskAndFreshScreen() {
        val g = GoalState("Open Calculator and calculate 37+58 using the buttons")
        val old = screen(g, "37")
        assertEquals(key + "add", g.fastResource(old))
        g.mutation()
        assertNull(g.fastResource(old))
        assertNull(g.fastResource(screen(g, "37", "other.app")))
        assertNull(GoalState("Open Calculator and calculate 37+58 and send it to Telegram").let {
            it.fastResource(screen(it, "37"))
        })
    }

    @Test fun fullLocalizedSequenceCanFinishOnlyAfterEquals() {
        val g = GoalState("Open Calculator and calculate 37+58 using the buttons")
        var before = screen(g, "")
        val transitions = listOf("03" to "3", "07" to "37", "add" to "37 Плюс ",
            "05" to "37 Плюс 5", "08" to "37 Плюс 58", "equal" to "95")
        for ((button, value) in transitions) {
            assertEquals(key + button, g.fastResource(before))
            assertFalse(g.fastFinish(before))
            g.mutation()
            val after = screen(g, value)
            assertTrue(g.afterClick(before, key + button, after))
            before = after
        }
        assertTrue(g.fastFinish(before))
        assertEquals("В калькуляторе подтверждено: 37+58 = 95.", g.finish(screen(g,"95")))
    }

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
    @Test fun postClickMustMatchExpectedFormulaTransition() {
        val g = GoalState("Open Calculator and calculate 2+2")
        val before = screen(g,"2")
        g.mutation()
        assertFalse(g.afterClick(before,key + "add",screen(g,"22")))
        assertFalse(g.afterClick(before,key + "add",screen(g,"2")))
        assertTrue(g.afterClick(before,key + "add",screen(g,"2+")))
    }
    @Test fun clearAcceptsRealSamsungEmptyFieldAndThenAllowsFirstDigit() {
        val g = GoalState("Open Calculator and calculate 37+58")
        val before = screen(g,"22")
        g.mutation()
        val empty = screen(g,"")
        assertTrue(g.afterClick(before,key + "clear",empty))
        assertNull(g.reject(empty,"click",key + "03"))
        assertNotNull(g.reject(empty,"click",key + "07"))
        g.mutation()
        assertTrue(g.afterClick(empty,key + "03",screen(g,"3")))
    }
    @Test fun missingOrUnrecognizedFieldIsNotAnEmptyField() {
        val g = GoalState("Open Calculator and calculate 2+2")
        val before = screen(g,"22")
        g.mutation()
        assertFalse(g.afterClick(before,key + "clear",GoalObservation(g.sequence(),pkg,emptyList())))
        assertFalse(g.afterClick(before,key + "clear",screen(g,"loading")))
        assertFalse(g.afterClick(before,key + "clear",screen(g,"","other.app")))
        assertTrue(g.afterClick(before,key + "clear",screen(g,"0")))
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
        for (request in listOf("Открой Калькулятор", "Open Calculator", "Открой Калькулятор.", "Open Calculator.", "Open Calculator!", "Open Calculator?")) {
            val g = GoalState(request)
            g.launched(pkg,"Калькулятор")
            assertNotNull(g.finish(screen(g,"0")))
            assertNull(g.finish(screen(g,"0","other.app")))
        }
        val other = GoalState("Open Telegram.")
        other.launched(pkg,"Калькулятор")
        assertNull(other.finish(screen(other,"0")))

        val dottedPackage = GoalState("Open com.example.app.")
        dottedPackage.launched("com.example.app", "Different label")
        assertNotNull(dottedPackage.finish(screen(dottedPackage,"0", "com.example.app")))
    }
    @Test fun openTelegramAliasesRequireMatchingActualApp() {
        val telegram = "org.telegram.messenger"
        for (request in listOf("Открой тг", "Открой телеграм", "Открой Telegram")) {
            val goal = GoalState(request)
            goal.launched(telegram, "Telegram")
            assertNotNull(goal.finish(screen(goal, "", telegram)))
            assertNull(goal.finish(screen(goal, "", pkg)))

            val wrong = GoalState(request)
            wrong.launched(pkg, "Калькулятор")
            assertNull(wrong.finish(screen(wrong, "", pkg)))
        }
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
