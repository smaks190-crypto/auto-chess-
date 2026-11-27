package dev.nox.local

/** A short-lived, in-memory handoff from the system assistant panel to the visible chat.
 * No external intent, transcript, or previous task authorizes microphone activation. */
internal class AssistantLaunchGate(
    private val now: () -> Long,
    private val newToken: () -> String,
    private val ttlMs: Long = 5000L
) {
    private var pending: String? = null
    private var deadline = 0L
    init { require(ttlMs > 0) }

    @Synchronized fun issue(activeAssistant: Boolean, unlocked: Boolean): String? {
        revoke()
        if (!activeAssistant || !unlocked) return null
        return newToken().also { pending = it; deadline = now() + ttlMs }
    }

    @Synchronized fun consume(token: String?, activeAssistant: Boolean, unlocked: Boolean): Boolean {
        if (!activeAssistant || !unlocked || now() >= deadline) {
            revoke()
            return false
        }
        if (token == null || token != pending) return false
        revoke()
        return true
    }

    @Synchronized fun revoke() { pending = null; deadline = 0L }
}
