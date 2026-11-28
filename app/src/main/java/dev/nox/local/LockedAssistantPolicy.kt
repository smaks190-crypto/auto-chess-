package dev.nox.local

internal object LockedAssistantPolicy {
    fun sessionAllowed(explicit: Boolean, activeRole: Boolean, interactive: Boolean?,
                       keyguard: Boolean?, deviceLocked: Boolean?): Boolean =
        explicit && activeRole && interactive == true && keyguard != null && deviceLocked != null &&
            (keyguard || deviceLocked)

    private fun normalize(input: String) = input.trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("^(?:нокс|nox)[ ,!:.]+"), "").trim().trimEnd('.', '!', '?', ' ')
    private fun command(input: String) = normalize(input).replace(Regex("\\s+"), " ")
        .replace(Regex("^(?:please|пожалуйста)[, ]+"), "")
        .replace(Regex("[, ]+(?:please|пожалуйста)$"), "")
    private val englishTimer = Regex("^(?:set|start|create)\\s+(?:a |the )?timer\\s+for\\s+(.+)$")
    private val englishPart = Regex("(\\d{1,4}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty|thirty|forty|sixty)\\s+(hours?|minutes?|seconds?|hrs?|mins?|secs?)")
    private val englishWords = mapOf("a" to 1,"an" to 1,"one" to 1,"two" to 2,"three" to 3,"four" to 4,"five" to 5,
        "six" to 6,"seven" to 7,"eight" to 8,"nine" to 9,"ten" to 10,"fifteen" to 15,"twenty" to 20,
        "thirty" to 30,"forty" to 40,"sixty" to 60)
    private val timer = Regex("^(?:поставь|установи|запусти|включи|создай)\\s+таймер\\s+на\\s+(.+)$")
    private val part = Regex("(\\d{1,4}|одну|один|одна|две|два|три|четыре|пять|шесть|семь|восемь|девять|десять|пятнадцать|двадцать|тридцать|сорок|шестьдесят)?\\s*(час(?:а|ов)?|минут(?:а|у|ы)?|секунд(?:а|у|ы)?)")
    private val words = mapOf("одну" to 1,"один" to 1,"одна" to 1,"две" to 2,"два" to 2,
        "три" to 3,"четыре" to 4,"пять" to 5,"шесть" to 6,"семь" to 7,"восемь" to 8,"девять" to 9,
        "десять" to 10,"пятнадцать" to 15,"двадцать" to 20,"тридцать" to 30,"сорок" to 40,"шестьдесят" to 60)
    fun timerRequested(input: String): Boolean =
        Regex("^(?:поставь|установи|запусти|включи|создай)\\s+(?:.*\\s+)?таймер(?:\\s|$)").containsMatchIn(command(input)) ||
            Regex("^(?:set|start|create)\\s+(?:a |the )?timer(?:\\s|$)").containsMatchIn(command(input))
    fun timerSeconds(input: String): Int? {
        val normalized = command(input)
        val en = englishTimer.matchEntire(normalized)?.groupValues?.get(1)
        val duration = en ?: timer.matchEntire(normalized)?.groupValues?.get(1) ?: return null
        var remaining = duration
        var seconds = 0L
        val units = mutableSetOf<Int>()
        while (remaining.isNotBlank()) {
            val m = (if (en != null) englishPart else part).find(remaining)?.takeIf { it.range.first == 0 } ?: return null
            val n = m.groupValues[1].let { if (it.isBlank()) 1 else it.toIntOrNull() ?: (if (en != null) englishWords[it] else words[it]) ?: return null }
            val unit = when {
                m.groupValues[2].startsWith("час") || m.groupValues[2].startsWith("h") -> 3600
                m.groupValues[2].startsWith("минут") || m.groupValues[2].startsWith("min") -> 60
                else -> 1
            }
            if (n <= 0 || !units.add(unit)) return null
            seconds += n.toLong() * unit
            remaining = remaining.substring(m.range.last + 1).trim()
        }
        return seconds.takeIf { it in 1L..3600L }?.toInt()
    }
    fun cancelTimerRequested(input: String): Boolean =
        command(input) in setOf("отмени таймер", "останови таймер", "выключи таймер", "cancel timer", "cancel the timer", "stop timer", "stop the timer")

    fun publicSearch(input: String): Boolean =
        Regex("^(?:погугли|найди в интернете|поищи в интернете|найди в сети|поищи в сети|проверь онлайн)(?:\\s|$)").containsMatchIn(normalize(input))
    fun needsUnlock(input: String): Boolean =
        Regex("^(?:открой|запусти|нажми|прокрути|отправь|позвони|разблокируй|удали|прочитай сообщения|прочитай экран|посмотри на экран|покажи мои|найди чат|напиши .+ в телеграм|open|launch|click|tap|scroll|send|call|unlock|delete|read messages|read the screen)(?:\\s|$)").containsMatchIn(normalize(input))
}
