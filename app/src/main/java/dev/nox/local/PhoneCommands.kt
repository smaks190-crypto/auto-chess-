package dev.nox.local

import java.util.Locale

internal sealed interface PhoneCommand {
    data class Open(val app: String): PhoneCommand
    data class Volume(val direction: Int): PhoneCommand
    data object Home: PhoneCommand
    data object Back: PhoneCommand
    data object Greeting: PhoneCommand
}

internal object PhoneCommands {
    fun parse(input: String): PhoneCommand? {
        val text = input.trim().lowercase(Locale.ROOT)
            .replace(Regex("^нокс[ ,!:.]+"), "")
            .trim().trimEnd('!', '.', '?', ' ')
        return when (text) {
            "привет", "здорово", "здравствуй", "здравствуйте" -> PhoneCommand.Greeting
            "громче", "увеличь громкость" -> PhoneCommand.Volume(1)
            "тише", "уменьши громкость" -> PhoneCommand.Volume(-1)
            "домой", "на главный экран", "главный экран" -> PhoneCommand.Home
            "назад", "вернись назад" -> PhoneCommand.Back
            else -> Regex("^(?:открой|запусти) ([\\p{L}\\p{N}][\\p{L}\\p{N} ._+-]{0,60})$")
                .matchEntire(text)?.groupValues?.get(1)?.trim()?.let { PhoneCommand.Open(it) }
        }
    }
}
