package dev.nox.local

import java.time.*
import java.util.Locale

internal sealed interface NativeCommand {
    data class Alarm(val hour: Int, val minute: Int, val days: Set<DayOfWeek>, val tomorrow: Boolean) : NativeCommand
    data class Volume(val delta: Int) : NativeCommand
    data class Torch(val enabled: Boolean) : NativeCommand
    data class Media(val action: String) : NativeCommand
    data class Stopwatch(val action: String) : NativeCommand
    data object CancelAlarm : NativeCommand
    data object AlarmInvalid : NativeCommand
    data object Battery : NativeCommand
    data object Time : NativeCommand
    data object Date : NativeCommand
    data object AlarmPermission : NativeCommand
}
internal object NativeCommands {
    private fun normalize(input: String) = input.trim().lowercase(Locale.ROOT)
        .replace(Regex("^(?:нокс|nox)[ ,!:.]+"), "").trim().trimEnd('.', '!', '?', ' ')
    private val numberWords = mapOf("ноль" to 0, "один" to 1, "час" to 1, "два" to 2, "три" to 3,
        "четыре" to 4, "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17,
        "восемнадцать" to 18, "девятнадцать" to 19, "двадцать" to 20, "двадцать один" to 21,
        "двадцать два" to 22, "двадцать три" to 23)
    private val minuteWords = numberWords + mapOf("тридцать" to 30, "сорок" to 40, "пятьдесят" to 50) +
        (listOf("двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50).flatMap { (word, tens) ->
            numberWords.filterValues { it in 1..9 }.filterKeys { it != "час" }.map { (unit, n) -> "$word $unit" to tens + n }
        }.toMap())
    private val weekdays = mapOf("понедельникам" to DayOfWeek.MONDAY, "вторникам" to DayOfWeek.TUESDAY,
        "средам" to DayOfWeek.WEDNESDAY, "четвергам" to DayOfWeek.THURSDAY, "пятницам" to DayOfWeek.FRIDAY,
        "субботам" to DayOfWeek.SATURDAY, "воскресеньям" to DayOfWeek.SUNDAY)
    private val prefix = Regex("^(?:поставь|установи|заведи|включи)\\s+будильник(?:\\s+|$)|^буди меня(?:\\s+|$)")
    private val pattern = Regex("^(?:(завтра|каждый день|ежедневно|по будням|по выходным|по [а-яё]+(?:,\\s*[а-яё]+)*)\\s+)?(?:на|в)\\s+(.+)$")
    fun parse(input: String): NativeCommand? {
        val text = normalize(input)
        when (text) {
            "отмени будильник нокса", "выключи будильник нокса", "отмени будильник", "выключи будильник" -> return NativeCommand.CancelAlarm
            "разрешение будильника", "настрой разрешение будильника" -> return NativeCommand.AlarmPermission
            "сколько заряда", "сколько процентов заряда", "уровень заряда", "заряд батареи", "сколько заряда телефона", "какой заряд батареи" -> return NativeCommand.Battery
            "который час", "сколько времени", "какое сейчас время", "сколько сейчас времени", "время" -> return NativeCommand.Time
            "какая сегодня дата", "какое сегодня число", "какой сегодня день", "дата" -> return NativeCommand.Date
            "громче", "увеличь громкость" -> return NativeCommand.Volume(1)
            "тише", "уменьши громкость" -> return NativeCommand.Volume(-1)
            "включи фонарик", "включи фонарь" -> return NativeCommand.Torch(true)
            "выключи фонарик", "выключи фонарь" -> return NativeCommand.Torch(false)
            "поставь музыку на паузу", "пауза музыки", "останови музыку" -> return NativeCommand.Media("pause")
            "продолжи музыку", "возобнови музыку" -> return NativeCommand.Media("play")
            "следующий трек", "следующая песня" -> return NativeCommand.Media("next")
            "предыдущий трек", "предыдущая песня" -> return NativeCommand.Media("previous")
            "запусти секундомер", "включи секундомер", "старт секундомера" -> return NativeCommand.Stopwatch("start")
            "останови секундомер", "поставь секундомер на паузу" -> return NativeCommand.Stopwatch("stop")
            "сбрось секундомер" -> return NativeCommand.Stopwatch("reset")
            "сколько на секундомере", "покажи секундомер" -> return NativeCommand.Stopwatch("read")
        }
        val start = prefix.find(text) ?: return null
        val body = text.substring(start.range.last + 1).trim()
        val match = pattern.matchEntire(body) ?: return NativeCommand.AlarmInvalid
        val repeat = match.groupValues[1]
        val days = when (repeat) {
            "", "завтра" -> emptySet()
            "каждый день", "ежедневно" -> DayOfWeek.values().toSet()
            "по будням" -> DayOfWeek.values().filter { it.value <= 5 }.toSet()
            "по выходным" -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            else -> repeat.removePrefix("по ").split(",").map { weekdays[it.trim()] ?: return NativeCommand.AlarmInvalid }.toSet()
        }
        var time = match.groupValues[2]
        val period = Regex("\\s+(утра|вечера|дня|ночи)$").find(time)
        if (period != null) time = time.substring(0, period.range.first)
        val numeric = Regex("(\\d{1,2})(?:(?::|\\s+)([0-5]\\d))?").matchEntire(time)
        val spoken = if (numeric == null && time !in numberWords) {
            val candidates = time.indices.filter { time[it] == ' ' }.mapNotNull { split ->
                val h = numberWords[time.substring(0, split)]
                val m = minuteWords[time.substring(split + 1)]
                if (h != null && m != null && m in 0..59) h to m else null
            }.distinct()
            candidates.singleOrNull()
        } else null
        var hour = numeric?.groupValues?.get(1)?.toIntOrNull() ?: numberWords[time] ?: spoken?.first
            ?: return NativeCommand.AlarmInvalid
        val minute = numeric?.groupValues?.get(2)?.toIntOrNull() ?: spoken?.second ?: 0
        if (period != null) {
            if (hour !in 1..12) return NativeCommand.AlarmInvalid
            hour = hour % 12 + if (period.groupValues[1] in setOf("вечера", "дня")) 12 else 0
        }
        if (hour !in 0..23) return NativeCommand.AlarmInvalid
        return NativeCommand.Alarm(hour, minute, days, repeat == "завтра")
    }

    fun nextAlarm(command: NativeCommand.Alarm, now: ZonedDateTime): ZonedDateTime {
        require(command.hour in 0..23 && command.minute in 0..59)
        val first = if (command.tomorrow) 1L else 0L
        for (offset in first..(if (command.tomorrow) 1L else 8L)) {
            val date = now.toLocalDate().plusDays(offset)
            if (command.days.isNotEmpty() && date.dayOfWeek !in command.days) continue
            val local = date.atTime(command.hour, command.minute)
            val candidate = local.atZone(now.zone)
            // Never silently schedule a shifted nonexistent DST time.
            if (candidate.toLocalDateTime() != local) continue
            if (candidate.isAfter(now)) return candidate
        }
        error("Не удалось определить время будильника")
    }
}
