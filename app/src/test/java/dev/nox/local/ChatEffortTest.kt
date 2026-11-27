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
    @Test fun matrixSelectsAndRejectsOutside() {
        assertEquals(MatrixChoice(2, ChatEffort.DEEP), MatrixChoice.at(99f,99f,100f,100f,3))
        assertEquals(MatrixChoice(2, ChatEffort.DEEP), MatrixChoice.at(100f,100f,100f,100f,3))
        assertNull(MatrixChoice.at(-1f,20f,100f,100f,3))
        assertNull(MatrixChoice.at(20f,110f,100f,100f,3))
        assertNull(MatrixChoice.at(0f,0f,0f,100f,3))
    }
}
