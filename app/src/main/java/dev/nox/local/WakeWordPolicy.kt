package dev.nox.local

internal enum class VoiceInputMode { WAKE, COMMAND, MANUAL, STOP_ONLY }

internal sealed interface WakeInput {
    object Ignore : WakeInput
    object Arm : WakeInput
    object Disable : WakeInput
    object CancelTask : WakeInput
    data class Command(val text: String) : WakeInput
}

/** Only final ASR utterances may activate voice commands. No fuzzy name matching. */
internal object WakeWordPolicy {
    private val name = Regex("^(?:нокс|nox)(?=$|[\\s,!.?:;—–-])", RegexOption.IGNORE_CASE)
    private fun afterName(text: String): String? {
        val match = name.find(text) ?: return null
        return text.substring(match.range.last + 1).trimStart { it.isWhitespace() || it in ",!.?:;—–-" }
    }

    fun route(mode: VoiceInputMode, raw: String): WakeInput {
        val text = raw.trim()
        if (text.isEmpty()) return WakeInput.Ignore
        val named = afterName(text)
        if ((mode == VoiceInputMode.WAKE || mode == VoiceInputMode.STOP_ONLY) && named == null)
            return WakeInput.Ignore
        val command = (named ?: text).trim()
        if (command.isEmpty()) return if (mode == VoiceInputMode.WAKE) WakeInput.Arm else WakeInput.Ignore
        if (VoiceLoopPolicy.isStopPhrase(command)) return WakeInput.Disable
        if (mode == VoiceInputMode.STOP_ONLY)
            return if (VoiceLoopPolicy.isCancelTaskPhrase(command)) WakeInput.CancelTask else WakeInput.Ignore
        return WakeInput.Command(command)
    }
}
