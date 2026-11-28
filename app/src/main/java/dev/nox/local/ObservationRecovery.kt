package dev.nox.local

/** The worker may extend read-only settling once for a transient service loss. */
internal class ObservationRecovery(private val now: () -> Long) {
    private val started = now()
    private var deadline = started + 3000L
    private var extended = false
    fun remaining(): Long = (deadline - now()).coerceAtLeast(0L)
    fun reconnecting(): Boolean {
        if (remaining() == 0L) return false
        if (!extended) {
            extended = true
            deadline = started + 7000L
        }
        return true
    }
}
