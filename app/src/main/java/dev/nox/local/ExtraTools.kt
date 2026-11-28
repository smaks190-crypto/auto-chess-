package dev.nox.local

import java.util.Locale

internal sealed interface ExtraCommand {
    data class Reminder(val seconds: Int?, val clock: NativeCommand.Alarm?, val text: String, val today: Boolean = false) : ExtraCommand
    data class CancelReminder(val id: Int) : ExtraCommand
    data object ReminderList : ExtraCommand
    data object ReminderInvalid : ExtraCommand
    data class Weather(val city: String, val tomorrow: Boolean) : ExtraCommand
    data object WeatherApp : ExtraCommand
    data class Search(val query: String, val encyclopedia: Boolean) : ExtraCommand
}
internal object ExtraTools {
    fun parse(raw: String): ExtraCommand? {
        val clean = raw.trim().replace(Regex("^(?:нокс|nox)[ ,!:.]+", RegexOption.IGNORE_CASE), "").trim().trimEnd('?', '!', '.')
        val lower = clean.lowercase(Locale.ROOT)
        if (lower in setOf("открой погоду", "открой приложение погода", "покажи погоду")) return ExtraCommand.WeatherApp
        if (lower in setOf("мои напоминания", "покажи напоминания", "список напоминаний")) return ExtraCommand.ReminderList
        Regex("^отмени напоминание (?:номер )?(\\d{1,9})$").matchEntire(lower)?.let {
            return ExtraCommand.CancelReminder(it.groupValues[1].toInt())
        }
        if (lower.startsWith("напомни ") || lower == "напомни") {
            val body = clean.substring(7).trim()
            if (body.startsWith("через ", true)) {
                val rest = body.substringAfter(' ').trim()
                for (i in rest.indices.reversed().filter { rest[it].isWhitespace() }) {
                    val seconds = LockedAssistantPolicy.timerSeconds("поставь таймер на " + rest.substring(0, i))
                    val text = rest.substring(i).trim().removePrefix("—").trim().removePrefix("что ").trim()
                    if (seconds != null && text.length in 1..300) return ExtraCommand.Reminder(seconds, null, text)
                }
            }
            val clock = Regex("^(?:(завтра|сегодня)\\s+)?(?:в|на)\\s+(\\d{1,2}(?::[0-5]\\d)?)(?:\\s+(утра|вечера|дня|ночи))?\\s+(?:—\\s*|что\\s+)?(.+)$", RegexOption.IGNORE_CASE).matchEntire(body)
            if (clock != null) {
                val time = clock.groupValues[2] + " " + clock.groupValues[3]
                val day = if (clock.groupValues[1].equals("завтра", true)) "завтра " else ""
                val parsed = NativeCommands.parse("поставь будильник " + day + "на " + time.trim()) as? NativeCommand.Alarm
                val text = clock.groupValues[4].trim()
                if (parsed != null && text.length in 1..300)
                    return ExtraCommand.Reminder(null, parsed, text, clock.groupValues[1].equals("сегодня", true))
            }
            return ExtraCommand.ReminderInvalid
        }
        val weather = Regex("^(?:какая\\s+)?(?:сейчас\\s+)?погода(?:\\s+(сегодня|завтра|сейчас))?(?:\\s+(?:в|для)\\s+(.+)|\\s+(.+))?$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (weather != null) return ExtraCommand.Weather((weather.groupValues[2].ifBlank { weather.groupValues[3] }).trim().take(100), weather.groupValues[1].equals("завтра", true))
        val wiki = Regex("^(?:найди|поищи)\\s+в\\s+википедии(?:\\s+(.+))?$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (wiki != null) return ExtraCommand.Search(wiki.groupValues[1].trim(), true)
        val search = Regex("^(?:погугли|найди в интернете|поищи в интернете|найди в сети|поищи в сети|проверь онлайн)(?:\\s+(.+))?$", RegexOption.IGNORE_CASE).matchEntire(clean)
        if (search != null) return ExtraCommand.Search(search.groupValues[1].trim(), false)
        return null
    }
}
