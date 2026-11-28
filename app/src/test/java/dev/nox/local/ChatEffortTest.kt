package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class ChatEffortTest {
    @Test fun budgetMatchesMode() {
        assertEquals(0, ChatEffort.FAST.budget("simple", false))
        assertEquals(256, ChatEffort.STANDARD.budget("simple", false))
        assertEquals(1024, ChatEffort.DEEP.budget("simple", false))
        assertEquals(0, ChatEffort.AUTO.budget("2+2", false))
        assertEquals(256, ChatEffort.AUTO.budget("x".repeat(121), false))
        assertEquals(256, ChatEffort.AUTO.budget("image", true))
    }
    @Test fun simpleDraftsAvoidAutomaticThinkingEvenWithLongInput() {
        assertEquals(0, ChatEffort.AUTO.budget("Перефразируй одним предложением: " + "данные ".repeat(80), false))
        assertEquals(0, ChatEffort.AUTO.budget("Письмо: встреча завтра. Напиши короткий ответ.", false))
        assertEquals(256, ChatEffort.AUTO.budget("Реши задачу и объясни результат", false))
    }
    @Test fun responseBudgetRespectsExplicitDepthAndDraftFormat() {
        assertEquals(160, ChatResponsePolicy.outputBudget("Перефразируй одним предложением: текст", false, 0))
        assertEquals(128, ChatResponsePolicy.outputBudget("Данные GitHub: PR 7 открыт", false, 0))
        assertEquals(2048, ChatResponsePolicy.outputBudget("Объясни подробно", false, 1024))
        assertTrue(ChatResponsePolicy.instruction("Напиши письмо").contains("готовый"))
        assertEquals(128, ChatEffort.AUTO.budget("Учебный бой: предложи перестановку", false))
        assertEquals(320, ChatResponsePolicy.outputBudget("Учебный бой: предложи перестановку", false, 128))
        assertEquals(416, ChatResponsePolicy.outputBudget("Перефразируй одним предложением: текст", false, 256))
    }
    @Test fun preservesUnambiguousDraftAndSelectsOnlyAnIntactAlternative() {
        val request = "Перефразируй одним предложением: встреча завтра"
        val list = "Вот несколько вариантов перефразирования:\n\n1. Встреча завтра.\n2. Завтра встречаемся."
        assertEquals("Встреча завтра.", ChatResponsePolicy.normalizeDraft(request, list))
        val prose = "Встреча завтра. Время не изменилось."
        assertEquals(prose, ChatResponsePolicy.normalizeDraft(request, prose))
        val incomplete = "Вот несколько вариантов:\n1. Встреча завтра\n2. Завтра встречаемся."
        assertEquals(incomplete, ChatResponsePolicy.normalizeDraft(request, incomplete))
        assertEquals(list, ChatResponsePolicy.normalizeDraft("Перефразируй: дай три варианта", list))
        val mail = "Вот несколько вариантов ответа:\n1. \"Здравствуйте! Перенесём встречу?\"\n2. \"Можно перенести встречу?\""
        assertEquals("Здравствуйте! Перенесём встречу?", ChatResponsePolicy.normalizeDraft("Письмо: напиши короткий ответ", mail))
    }
    @Test fun recoveryRetriesOnlyBlankOnceAndNeverAfterCancellationOrError() {
        val attempts = mutableListOf<Triple<Int, Int, Boolean>>()
        val result = ChatRecovery.run(128, 320, 192, { false }) { thinking, limit, retry ->
            attempts.add(Triple(thinking, limit, retry))
            if (retry) "Ответ." else " "
        }
        assertEquals("Ответ.", result)
        assertEquals(listOf(Triple(128, 320, false), Triple(0, 192, true)), attempts)
        var count = 0
        assertEquals("Готово", ChatRecovery.run(0, 160, 160, { false }) { _, _, _ -> count++; "Готово" })
        assertEquals(1, count)
        count = 0
        val cancelled = runCatching { ChatRecovery.run(128, 320, 192, { count > 0 }) { _, _, _ -> count++; "" } }
        assertTrue(cancelled.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertEquals(1, count)
        count = 0
        assertTrue(runCatching { ChatRecovery.run(128, 320, 192, { false }) { _, _, _ -> count++; error("native") } }.isFailure)
        assertEquals(1, count)
        count = 0
        assertTrue(runCatching { ChatRecovery.run(128, 320, 192, { false }) { _, _, _ -> count++; "" } }.isFailure)
        assertEquals(2, count)
    }
    @Test fun matrixSelectsAndRejectsOutside() {
        assertEquals(MatrixChoice(2, ChatEffort.DEEP), MatrixChoice.at(99f,99f,100f,100f,3))
        assertEquals(MatrixChoice(2, ChatEffort.DEEP), MatrixChoice.at(100f,100f,100f,100f,3))
        assertNull(MatrixChoice.at(-1f,20f,100f,100f,3))
        assertNull(MatrixChoice.at(20f,110f,100f,100f,3))
        assertNull(MatrixChoice.at(0f,0f,0f,100f,3))
    }
}
