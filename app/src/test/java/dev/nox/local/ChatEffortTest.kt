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
    }
    @Test fun matrixSelectsAndRejectsOutside() {
        assertEquals(MatrixChoice(2, ChatEffort.DEEP), MatrixChoice.at(99f,99f,100f,100f,3))
        assertEquals(MatrixChoice(2, ChatEffort.DEEP), MatrixChoice.at(100f,100f,100f,100f,3))
        assertNull(MatrixChoice.at(-1f,20f,100f,100f,3))
        assertNull(MatrixChoice.at(20f,110f,100f,100f,3))
        assertNull(MatrixChoice.at(0f,0f,0f,100f,3))
    }
}
