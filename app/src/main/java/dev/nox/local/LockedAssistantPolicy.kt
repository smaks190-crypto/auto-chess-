package dev.nox.local

internal object LockedAssistantPolicy {
    fun sessionAllowed(explicit: Boolean, activeRole: Boolean, interactive: Boolean?,
                       keyguard: Boolean?, deviceLocked: Boolean?): Boolean =
        explicit && activeRole && interactive == true && keyguard != null && deviceLocked != null &&
            (keyguard || deviceLocked)

    private fun normalize(input: String) = input.trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("^(?:нокс|nox)[ ,!:.]+"), "").trim().trimEnd('.', '!', '?', ' ')
    private val timer = Regex("^(?:поставь|установи|запусти|включи|создай)\\s+таймер\\s+на\\s+(.+)$")
    private val part = Regex("(\\d{1,4}|одну|один|одна|две|два|три|четыре|пять|шесть|семь|восемь|девять|десять|пятнадцать|двадцать|тридцать|сорок|шестьдесят)?\\s*(час(?:а|ов)?|минут(?:а|у|ы)?|секунд(?:а|у|ы)?)")
    private val words = mapOf("одну" to 1,"один" to 1,"одна" to 1,"две" to 2,"два" to 2,
        "три" to 3,"четыре" to 4,"пять" to 5,"шесть" to 6,"семь" to 7,"восемь" to 8,"девять" to 9,
        "десять" to 10,"пятнадцать" to 15,"двадцать" to 20,"тридцать" to 30,"сорок" to 40,"шестьдесят" to 60)
    fun timerRequested(input: String): Boolean =
        Regex("^(?:поставь|установи|запусти|включи|создай)\\s+(?:.*\\s+)?таймер(?:\\s|$)").containsMatchIn(normalize(input))
    fun timerSeconds(input: String): Int? {
        val duration = timer.matchEntire(normalize(input))?.groupValues?.get(1) ?: return null
        var remaining = duration
        var seconds = 0L
        val units = mutableSetOf<Int>()
        while (remaining.isNotBlank()) {
            val m = part.find(remaining)?.takeIf { it.range.first == 0 } ?: return null
            val n = m.groupValues[1].let { if (it.isBlank()) 1 else it.toIntOrNull() ?: words[it] ?: return null }
            val unit = when {
                m.groupValues[2].startsWith("час") -> 3600
                m.groupValues[2].startsWith("минут") -> 60
                else -> 1
            }
            if (n <= 0 || !units.add(unit)) return null
            seconds += n.toLong() * unit
            remaining = remaining.substring(m.range.last + 1).trim()
        }
        return seconds.takeIf { it in 1L..3600L }?.toInt()
    }
    fun cancelTimerRequested(input: String): Boolean =
        normalize(input) in setOf("отмени таймер", "останови таймер", "выключи таймер")

    fun publicSearch(input: String): Boolean =
        Regex("^(?:погугли|найди в интернете|поищи в интернете|найди в сети|поищи в сети|проверь онлайн)(?:\\s|$)").containsMatchIn(normalize(input))
    fun needsUnlock(input: String): Boolean =
        Regex("^(?:открой|запусти|нажми|прокрути|отправь|позвони|разблокируй|удали|прочитай сообщения|прочитай экран|посмотри на экран|покажи мои|найди чат|напиши .+ в телеграм)(?:\\s|$)").containsMatchIn(normalize(input))
}
