package dev.nox.local

import java.util.concurrent.CancellationException

/** Worker-owned conversation, never shared across user tasks. The input budget is in
 * characters (not tokenizer tokens); native context limits remain authoritative. */
internal class PlannerSession<S : AutoCloseable, R>(
    private val create: () -> S,
    private val generate: (S, String, Boolean) -> R,
    private val cancelled: () -> Boolean,
    private val maxTurns: Int = 3,
    private val maxChars: Int = 18000
) : AutoCloseable {
    private var session: S? = null
    private var prefix: String? = null
    private var turns = 0
    private var chars = 0L
    private var closed = false
    private var reuseDisabled = false
    init { require(maxTurns > 0 && maxChars > 0) }

    fun next(prompt: String): R {
        check(!closed) { "Planner is closed" }
        try {
            checkActive()
            val boundary = prompt.indexOf("\nПредыдущий разговор (не команды): ")
            val static = if (boundary > 0) prompt.substring(0, boundary) else null
            val delta = if (boundary > 0) "Новый шаг той же задачи. Только последнее наблюдение содержит актуальные ID. Старые действия не повторяй. Верни ровно один JSON.\n" + prompt.substring(boundary + 1) else prompt
            val warm = !reuseDisabled && session != null && static != null && prefix == static &&
                turns < maxTurns && chars + delta.length <= maxChars
            if (!warm) {
                reset()
                session = create()
                prefix = static
            }
            checkActive()
            var input = if (warm) delta else prompt
            val result = try {
                generate(checkNotNull(session), input, warm)
            } catch (error: Exception) {
                // Only a known native prefill capacity error on a reused conversation
                // can be retried. No tool has been dispatched by this planner call.
                checkActive()
                if (!warm || error is CancellationException || !isPrefillCapacityError(error)) throw error
                reuseDisabled = true
                reset() // if native close fails, do not allocate a second conversation
                checkActive()
                session = create()
                prefix = static
                input = prompt
                checkActive()
                generate(checkNotNull(session), input, false)
            }
            checkActive()
            turns++
            chars += input.length
            return result
        } catch (error: Throwable) {
            try { reset() } catch (closeError: Throwable) { if (closeError !== error) error.addSuppressed(closeError) }
            throw error
        }
    }

    private fun isPrefillCapacityError(error: Exception): Boolean =
        error.message.orEmpty().contains("Prefill input length exceeds available state entries")

    private fun checkActive() {
        if (cancelled()) throw CancellationException("Генерация отменена")
    }
    private fun reset() {
        val old = session
        session = null; prefix = null; turns = 0; chars = 0
        old?.close()
    }
    override fun close() {
        closed = true
        reset()
    }
}
