package dev.nox.local

internal data class ExpectedNode(val field: String, val value: String) {
    init {
        require(field in setOf("text", "hint", "description")) { "Недопустимое поле expect" }
        require(value.isNotBlank() && value.length <= 180 && value.none { it.isISOControl() }) { "Недопустимое значение expect" }
    }
}
internal data class EffectNode(val path: List<Int>, val resource: String, val kind: String,
    val text: String?, val hint: String, val description: String?, val editable: Boolean, val enabled: Boolean)
internal data class EffectFrame(val seq: Long, val pkg: String, val window: Int, val nodes: List<EffectNode>)

internal object EffectCheck {
    private fun count(frame: EffectFrame, expect: ExpectedNode): Int = frame.nodes.count {
        when (expect.field) {
            "text" -> it.text
            "hint" -> it.hint
            else -> it.description
        } == expect.value
    }
    fun clickRejection(before: EffectFrame, expect: ExpectedNode?): String? = when {
        expect == null -> "Укажи expect: точное новое поле text, hint или description."
        count(before, expect) != 0 -> "Ожидаемый элемент уже есть на экране; выбери другое проверяемое изменение."
        else -> null
    }
    private fun fresh(before: EffectFrame, mutation: Long, after: EffectFrame): Boolean =
        before.seq < mutation && mutation < after.seq && before.pkg == after.pkg
    fun clicked(before: EffectFrame, mutation: Long, after: EffectFrame, expect: ExpectedNode?): Boolean =
        expect != null && fresh(before, mutation, after) && count(before, expect) == 0 && count(after, expect) == 1
    fun typeRejection(target: EffectNode, text: String): String? = when {
        !target.editable || !target.enabled -> "Поле недоступно для ввода."
        target.text == text -> "В поле уже находится этот текст."
        else -> null
    }
    fun typed(before: EffectFrame, mutation: Long, after: EffectFrame, target: EffectNode, text: String): Boolean {
        if (!fresh(before, mutation, after) || before.window != after.window || typeRejection(target, text) != null) return false
        if (before.nodes.singleOrNull { it.path == target.path } != target) return false
        val current = after.nodes.singleOrNull { it.path == target.path && it.resource == target.resource && it.kind == target.kind }
        return current != null && current.editable && current.enabled && current.text == text
    }
}
