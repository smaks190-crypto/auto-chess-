package dev.nox.local

import java.util.Locale

internal sealed interface PhoneCommand {
    data class Open(val app: String): PhoneCommand
    data class OpenWithFollowUp(val app: String, val followUp: String): PhoneCommand
    data class Volume(val direction: Int): PhoneCommand
    data object Home: PhoneCommand
    data object Back: PhoneCommand
    data object Greeting: PhoneCommand
}

internal object PhoneCommands {
    private const val APP = "[\\p{L}\\p{N}][\\p{L}\\p{N} ._+-]{0,60}"
    private val compound = Regex("^(?:открой|запусти)\\s+($APP?)(?:\\s*,\\s*|\\s+)(?:(?:и|а)\\s+)?(?:потом\\s+)?((?:напиши|отправь|найди|нажми|позвони|введи|открой|запусти)(?:\\s+.*)?)$")
    private val englishOpen = Regex("^(?:open|launch|start)\\s+(?:the\\s+)?($APP)$")
    private val open = Regex("^(?:открой|запусти)\\s+($APP)$")
    private val reverseOpen = Regex("^($APP)\\s+(?:открой|запусти)$")

    fun parse(input: String): PhoneCommand? {
        val text = input.trim().lowercase(Locale.ROOT)
            .replace(Regex("^(?:нокс|nox)[ ,!:.]+"), "")
            .trim().trimEnd('!', '.', '?', ' ')
            .replace(Regex("^(?:please|пожалуйста)[, ]+"), "")
            .replace(Regex("[, ]+(?:please|пожалуйста)$"), "")
        return when (text) {
            "привет", "здорово", "здравствуй", "здравствуйте" -> PhoneCommand.Greeting
            "громче", "увеличь громкость" -> PhoneCommand.Volume(1)
            "тише", "уменьши громкость" -> PhoneCommand.Volume(-1)
            "домой", "на главный экран", "главный экран" -> PhoneCommand.Home
            "назад", "вернись назад" -> PhoneCommand.Back
            else -> {
                compound.matchEntire(text)?.let {
                    return PhoneCommand.OpenWithFollowUp(it.groupValues[1].trim(), it.groupValues[2].trim())
                }
                val app = (open.matchEntire(text) ?: reverseOpen.matchEntire(text) ?: englishOpen.matchEntire(text))
                    ?.groupValues?.get(1)?.trim()
                // Never route an unrecognised multi-action phrase to the package lookup.
                if (app == null || Regex("\\s+(?:и|потом|затем|and|then|or|after|before|without|но)\\s+").containsMatchIn(app)) null
                else PhoneCommand.Open(app)
            }
        }
    }
}
