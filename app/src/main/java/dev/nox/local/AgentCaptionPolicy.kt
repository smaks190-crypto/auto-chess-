package dev.nox.local

internal data class AgentCaption(val text: String, val stoppable: Boolean)

internal object AgentCaptionPolicy {
    fun select(chatVisible: Boolean, action: String?, speech: String): AgentCaption? {
        if (chatVisible) return null
        if (!action.isNullOrBlank()) return AgentCaption(action, true)
        return speech.takeIf { it.isNotBlank() }?.let { AgentCaption(it, false) }
    }

    fun overlay(background: Boolean, agentRunning: Boolean, unlocked: Boolean, granted: Boolean) =
        (background || agentRunning) && unlocked && granted
}
