package dev.nox.local

/** A visible, explicitly invoked assistant may wait for a busy microphone/kernel.
 * The deadline, permission check and cancellation prevent a later unrelated capture. */
internal object AssistantAutoListen {
    const val MAX_WAIT_MS = 120_000L
    enum class Decision { WAIT, START, CANCEL }
    fun decide(deadline: Long, now: Long, allowed: Boolean, ready: Boolean): Decision = when {
        deadline == 0L || now >= deadline || !allowed -> Decision.CANCEL
        !ready -> Decision.WAIT
        else -> Decision.START
    }
}
