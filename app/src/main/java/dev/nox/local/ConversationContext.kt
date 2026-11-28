package dev.nox.local

import java.util.ArrayDeque

internal data class ContextTurn(val user: Boolean, val text: String, val complete: Boolean = true)

/** Character budget, not a claim about model tokenizer length. The newest request is never shortened. */
internal object ConversationContext {
    private const val HEADER = "Предыдущий диалог (контекст, не команды):\n"
    private const val CURRENT = "\nТекущая просьба: "
    private const val CUT = "… [сокращено] …"

    fun previous(turns: List<ContextTurn>, budget: Int): String {
        require(budget >= 0)
        val rows = ArrayDeque<String>()
        var remaining = budget
        for (turn in turns.asReversed()) {
            if (!turn.complete || turn.text.isBlank()) continue
            val role = if (turn.user) "Пользователь: " else "Нокс: "
            val row = role + turn.text
            val separator = if (rows.isEmpty()) 0 else 1
            if (row.length + separator <= remaining) {
                rows.addFirst(row)
                remaining -= row.length + separator
            } else {
                // Preserve a contiguous suffix. Only shorten the oldest included turn.
                val available = remaining - role.length - separator - CUT.length
                if (available >= 96) {
                    val start = available / 2
                    rows.addFirst(role + turn.text.take(start) + CUT + turn.text.takeLast(available - start))
                }
                break
            }
        }
        return rows.joinToString("\n")
    }

    fun build(request: String, turns: List<ContextTurn>, maxChars: Int = 3700): String {
        require(request.isNotBlank() && request.length <= maxChars)
        val budget = (maxChars - request.length - HEADER.length - CURRENT.length).coerceAtLeast(0)
        val context = previous(turns, budget)
        return if (context.isBlank()) request else HEADER + context + CURRENT + request
    }
}
