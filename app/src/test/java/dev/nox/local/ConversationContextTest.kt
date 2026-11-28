package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class ConversationContextTest {
    @Test fun retainsMoreThanFourTurnsAndFullRecentText() {
        val long = "a".repeat(600) + "END"
        val turns = (1..6).flatMap { listOf(ContextTurn(true,"user-$it"), ContextTurn(false,if(it==6)long else "reply-$it")) }
        val result = ConversationContext.build("Follow up", turns)
        assertTrue(result.contains("user-1"))
        assertTrue(result.contains(long))
        assertTrue(result.endsWith("Follow up"))
    }
    @Test fun failedOrInterruptedRepliesAreNotFacts() {
        assertFalse(ConversationContext.build("Question", listOf(ContextTurn(false,"unverified",false))).contains("unverified"))
    }
    @Test fun keepsEntireCurrentRequestWithinExistingModelBudget() {
        val request = "x".repeat(2000)
        val result = ConversationContext.build(request, listOf(ContextTurn(false,"z".repeat(9000))))
        assertTrue(result.length <= 3700)
        assertTrue(result.endsWith(request))
        assertTrue(result.contains("сокращено"))
    }
}
