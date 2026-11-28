package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class EffectCheckTest {
    private val button = EffectNode(listOf(1), "button", "Button", null, "", "Search", false, true)
    private val field = EffectNode(listOf(2), "field", "EditText", "", "Search chats", null, true, true)
    private val before = EffectFrame(1, "app", 7, listOf(button))
    private val after = EffectFrame(3, "app", 8, listOf(field))
    private val e = ExpectedNode("hint", "Search chats")
    @Test fun suppliedExpectationMustBeNewBeforeDispatch() {
        assertNull(EffectCheck.clickRejection(before, null))
        assertNull(EffectCheck.clickRejection(before, e))
        assertNotNull(EffectCheck.clickRejection(before.copy(nodes = listOf(field)), e))
    }
    @Test fun onlyUniqueNewNodeOnFreshAuthorizedScreenVerifiesClick() {
        assertTrue(EffectCheck.clicked(before, 2, after, e))
        assertFalse(EffectCheck.clicked(before, 2, after.copy(nodes = listOf(button)), e))
        assertFalse(EffectCheck.clicked(before, 2, after.copy(seq = 2), e))
        assertFalse(EffectCheck.clicked(before, 2, after.copy(pkg = "other"), e))
        assertFalse(EffectCheck.clicked(before.copy(nodes = listOf(field)), 2, after, e))
        assertFalse(EffectCheck.clicked(before, 2, after.copy(nodes = listOf(field, field.copy(path = listOf(4)))), e))
        assertTrue(EffectCheck.clicked(before, 2, after, null))
        assertFalse(EffectCheck.clicked(before, 2, after.copy(seq = 2), null))
        assertFalse(EffectCheck.clicked(before, 2, after.copy(pkg = "other"), null))
        assertFalse(EffectCheck.clicked(before, 2, before.copy(seq = 3), null))
        assertFalse(EffectCheck.clicked(before, 2, before.copy(seq = 3,
            nodes = listOf(button.copy(text = "clock 12:01"))), null))
        assertFalse(EffectCheck.clicked(before, 2, after.copy(nodes = emptyList()), null))
    }
    @Test fun exactInputMustAppearInSameFieldNotAnotherNode() {
        val input = after.copy(seq = 4)
        val typed = input.copy(seq = 6, nodes = listOf(field.copy(text = "hello")))
        assertTrue(EffectCheck.typed(input, 5, typed, field, "hello"))
        assertFalse(EffectCheck.typed(input, 5, typed.copy(nodes = listOf(field.copy(path = listOf(4), text = "hello"))), field, "hello"))
        assertFalse(EffectCheck.typed(input, 5, typed.copy(window = 9), field, "hello"))
        assertFalse(EffectCheck.typed(input, 5, typed.copy(nodes = listOf(field.copy(text = "HELLO"))), field, "hello"))
        assertNotNull(EffectCheck.typeRejection(field.copy(text = "hello"), "hello"))
    }
    @Test fun uniqueSearchFieldCanMoveWhenResultsAppear() {
        val input = after.copy(seq = 4)
        val moved = field.copy(path = listOf(8, 1), text = "hello")
        val result = input.copy(seq = 6, nodes = listOf(moved))
        assertFalse(EffectCheck.typed(input, 5, result, field, "hello"))
        assertTrue(EffectCheck.searchTyped(input, 5, result, field, moved, "hello"))
        assertFalse(EffectCheck.searchTyped(input, 5, result.copy(pkg = "other"), field, moved, "hello"))
        assertFalse(EffectCheck.searchTyped(input, 5, result.copy(window = 99), field, moved, "hello"))
        assertFalse(EffectCheck.searchTyped(input, 5, result.copy(seq = 5), field, moved, "hello"))
        assertFalse(EffectCheck.searchTyped(input, 5, result.copy(nodes = listOf(moved, moved.copy(path = listOf(9)))), field, moved, "hello"))
        assertFalse(EffectCheck.searchTyped(input, 5, result.copy(nodes = listOf(moved.copy(resource = "other"))), field, moved.copy(resource = "other"), "hello"))
        assertFalse(EffectCheck.searchTyped(input, 5, result.copy(nodes = listOf(moved.copy(hint = "Message"))), field, moved.copy(hint = "Message"), "hello"))
        assertFalse(EffectCheck.searchTyped(input, 5, result.copy(nodes = listOf(moved.copy(text = "wrong"))), field, moved.copy(text = "wrong"), "hello"))
    }
    @Test fun searchMismatchReportsOnlyStructuralReason() {
        val input = after.copy(seq = 4)
        val moved = field.copy(path = listOf(9), text = "hello")
        val frame = input.copy(seq = 6, nodes = listOf(moved))
        assertNull(EffectCheck.searchMismatch(input, 5, frame, field, moved, "hello"))
        assertEquals("TEXT_MISMATCH", EffectCheck.searchMismatch(input, 5, frame, field, moved, "other"))
        val changed = moved.copy(description = "different")
        assertEquals("SEMANTIC_IDENTITY", EffectCheck.searchMismatch(input, 5, frame.copy(nodes = listOf(changed)), field, changed, "hello"))
        assertEquals("AMBIGUOUS_FIELD", EffectCheck.searchMismatch(input, 5, frame.copy(nodes = listOf(moved, moved.copy(path = listOf(10)))), field, moved, "hello"))
    }
    @Test fun expectationDoesNotAcceptArbitraryFieldsOrEmptyValues() {
        assertThrows(IllegalArgumentException::class.java) { ExpectedNode("unknown", "x") }
        assertThrows(IllegalArgumentException::class.java) { ExpectedNode("text", "\n") }
    }
}
