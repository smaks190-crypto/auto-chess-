package dev.nox.local

/** Only a real final recognition in the explicitly opened system panel may auto-submit. */
internal object AssistantVoiceCommand {
    fun useChat(visible: Boolean, onScreen: Boolean, topResumed: Boolean, multiWindow: Boolean,
                activeAssistant: Boolean, unlocked: Boolean): Boolean =
        visible && onScreen && (!multiWindow || topResumed) && activeAssistant && unlocked

    /** Explicit compact requests may use the same guarded agent as the main chat.
     * The active role and unlocked screen remain mandatory throughout execution. */
    fun canRunAgent(activeAssistant: Boolean, unlocked: Boolean): Boolean =
        activeAssistant && unlocked

    /** Never substitute UI navigation or fabricated results for unavailable native tools. */
    fun dedicatedToolUnavailable(text: String): String? {
        val normalized = text.trim().lowercase(java.util.Locale.ROOT)
        return when {
            Regex("^(?:поставь|установи|запусти|включи|создай)\\s+.*таймер").containsMatchIn(normalized) ->
                "Встроенный таймер ещё недоступен. Таймер не установлен."
            Regex("^(?:найди|поищи|погугли|проверь|узнай)\\b.*(?:интернет|сети|онлайн|google|гугл)|^погугли\\b").containsMatchIn(normalized) ->
                "Прямой интернет-поиск ещё недоступен. Поиск не выполнен."
            else -> null
        }
    }

    /** Explicit read-only screen question, never a multi-action instruction. */
    fun isScreenRequest(text: String): Boolean {
        val normalized = text.trim().lowercase(java.util.Locale.ROOT)
            .replace(Regex("^(?:нокс|nox)[ ,!:.]+"), "").trim().trimEnd('.', '!', '?', ' ')
        return normalized in setOf("посмотри на экран", "посмотри экран", "что на экране",
            "опиши экран", "что ты видишь на экране", "покажи что на экране")
    }

    fun fromResult(armed: Boolean, finalized: Boolean, text: String): String? {
        if (!armed || !finalized || text.length > 2000) return null
        return (WakeWordPolicy.route(VoiceInputMode.MANUAL, text) as? WakeInput.Command)?.text?.takeUnless(VoiceLoopPolicy::isCancelTaskPhrase)
    }
}
