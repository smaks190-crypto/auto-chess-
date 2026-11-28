package dev.nox.local

/** The executor, not the language model, owns the final cancellation gate. */
internal class AgentActionGate(
    private val cancelled: () -> Boolean,
    private val announce: (String) -> Unit
) {
    fun before(label: String, requiresConfirmation: Boolean = false, confirm: (() -> Unit)? = null) {
        if (cancelled()) throw AgentDenied()
        announce(label)
        if (cancelled()) throw AgentDenied()
        if (requiresConfirmation) {
            requireNotNull(confirm) { "Explicit approval callback required" }.invoke()
            if (cancelled()) throw AgentDenied()
            announce(label)
            if (cancelled()) throw AgentDenied()
        }
    }
}
