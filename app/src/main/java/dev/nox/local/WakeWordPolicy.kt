package dev.nox.local

internal enum class VoiceInputMode { WAKE, MANUAL, LOCKED_WAKE }

internal sealed interface WakeInput {
    object Ignore : WakeInput
    object ManualRequired : WakeInput
    object UnlockRequired : WakeInput
    data class Command(val text: String) : WakeInput
}

/** Background speech can signal a wake word. Only a user-started session can submit a command. */
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
        return when (mode) {
            VoiceInputMode.LOCKED_WAKE -> if (named == null) WakeInput.Ignore else WakeInput.UnlockRequired
            VoiceInputMode.WAKE -> if (named == null) WakeInput.Ignore else WakeInput.ManualRequired
            VoiceInputMode.MANUAL -> {
                val command = (named ?: text).trim()
                if (command.isEmpty() || VoiceLoopPolicy.isStopPhrase(command)) WakeInput.Ignore
                else WakeInput.Command(command)
            }
        }
    }
}
