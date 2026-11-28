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
    @Test fun expectationDoesNotAcceptArbitraryFieldsOrEmptyValues() {
        assertThrows(IllegalArgumentException::class.java) { ExpectedNode("unknown", "x") }
        assertThrows(IllegalArgumentException::class.java) { ExpectedNode("text", "\n") }
    }
}
