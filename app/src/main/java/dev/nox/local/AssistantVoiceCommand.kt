package dev.nox.local

/** Only a real final recognition in the explicitly opened system panel may auto-submit. */
internal object AssistantVoiceCommand {
    fun useChat(visible: Boolean, onScreen: Boolean, topResumed: Boolean, multiWindow: Boolean,
                activeAssistant: Boolean, unlocked: Boolean): Boolean =
        visible && onScreen && (!multiWindow || topResumed) && activeAssistant && unlocked

    fun fromResult(armed: Boolean, finalized: Boolean, text: String): String? {
        if (!armed || !finalized || text.length > 2000) return null
        return (WakeWordPolicy.route(VoiceInputMode.MANUAL, text) as? WakeInput.Command)?.text?.takeUnless(VoiceLoopPolicy::isCancelTaskPhrase)
    }
}
