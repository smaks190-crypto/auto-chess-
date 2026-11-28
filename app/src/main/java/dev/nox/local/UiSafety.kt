package dev.nox.local

import java.util.concurrent.atomic.AtomicBoolean

/** Monotonic deadline shared by the waiting worker and the main-thread operation. */
internal class UiOperationBudget(
    private val timeoutMs: Long,
    private val now: () -> Long
) {
    val posted = now()
    @Volatile var started = -1L
        private set
    @Volatile var ended = -1L
        private set
    @Volatile var stage = "QUEUED"
    @Volatile var rootMs = 0L
    @Volatile var walkMs = 0L
    @Volatile var nodes = 0
    @Volatile var childCalls = 0
    private val cancelled = AtomicBoolean(false)
    fun remaining(): Long = (timeoutMs - (now() - posted)).coerceAtLeast(0)
    fun start() { started = now(); stage = "STARTED" }
    fun end() { ended = now() }
    fun cancel() { cancelled.set(true) }
    fun expired(): Boolean = cancelled.get() || remaining() == 0L
    fun check() { if (expired()) throw AgentUiTimeout() }
    fun timeoutState(): String = when {
        started < 0 -> "NEVER_STARTED"
        ended >= 0 -> "FINISHED"
        else -> "RUNNING"
    }
    fun queueMs(): Long = ((if (started >= 0) started else now()) - posted).coerceAtLeast(0)
    fun runMs(): Long = if (started < 0) 0L else ((if (ended >= 0) ended else now()) - started).coerceAtLeast(0)
}

internal data class NodeIdentity(
    val pkg: String, val resource: String, val cls: String,
    val text: String?, val description: String?, val bounds: String,
    val enabled: Boolean, val visible: Boolean, val password: Boolean,
    val clickable: Boolean, val editable: Boolean, val scrollable: Boolean
) {
    // Use only after a unique semantic search control was selected in the same app/window.
    // A layout change may move it, but text, class, resource and capabilities must still match.
    fun searchRelocationMismatch(actual: NodeIdentity): String? =
        mismatch(actual.copy(bounds = bounds))

    fun mismatch(actual: NodeIdentity): String? = when {
        pkg != actual.pkg -> "PKG"
        resource != actual.resource -> "RESID"
        cls != actual.cls -> "CLASS"
        actual.password -> "PASSWORD"
        text != actual.text || description != actual.description -> "LABEL"
        bounds != actual.bounds -> "BOUNDS"
        !actual.enabled -> "DISABLED"
        !actual.visible -> "HIDDEN"
        clickable != actual.clickable || editable != actual.editable ||
            scrollable != actual.scrollable -> "CAPABILITY"
        else -> null
    }
}

/** Only thrown before the platform mutation is dispatched. */
internal class NodeChanged(val reason: String) : IllegalStateException("NODE_CHANGED:$reason")

internal inline fun <T> withVerifiedNode(expected: NodeIdentity, actual: NodeIdentity, dispatch: () -> T): T {
    expected.mismatch(actual)?.let { throw NodeChanged(it) }
    return dispatch()
}
