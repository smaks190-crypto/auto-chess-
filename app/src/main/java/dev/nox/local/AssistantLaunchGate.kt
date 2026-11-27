package dev.nox.local

/** A short-lived, in-memory handoff from the system assistant panel to the visible chat.
 * No external intent, transcript, or previous task authorizes microphone activation. */
internal class AssistantLaunchGate(
    private val now: () -> Long,
    private val newToken: () -> String,
    private val ttlMs: Long = 5000L
) {
    private var pending: String? = null
    private var pendingDraft: String? = null
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

    @Synchronized fun issueDraft(activeAssistant: Boolean, unlocked: Boolean, text: String): String? {
        if (text.isBlank()) { revoke(); return null }
        val token = issue(activeAssistant, unlocked) ?: return null
        pendingDraft = text.take(2000)
        return token
    }

    @Synchronized fun consumeDraft(token: String?, activeAssistant: Boolean, unlocked: Boolean): String? {
        val value = pendingDraft
        return if (consume(token, activeAssistant, unlocked)) value else null
    }

    @Synchronized fun expire(token: String) {
        if (pending == token) revoke()
    }

    @Synchronized fun revoke() { pending = null; pendingDraft = null; deadline = 0L }
}
