package dev.nox.local

/** Only explicit Telegram current-chat requests; never infer a recipient or send. */
internal object TelegramDraft {
    data class Request(val text: String, val send: Boolean)
    fun isTelegram(pkg: String): Boolean = pkg in setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")

    private const val APP = "(?:телеграм(?:е|м|ме)?|telegram|тг)"
    fun openWriteNeedsClarification(input: String): Boolean =
        Regex("^(?:открой|запусти)\\s+(?:телеграмм?|telegram|тг)\\s+и\\s+(?:напиши|отправь)\\s+.+$", RegexOption.IGNORE_CASE)
            .matches(input.trim()) && SearchNavigation.request(input) == null
    private val suffix = Regex("^(?:напиши|напечатай|введи|write|type|отправь|send)\\s+(.+?)\\s+(?:в\\s+(?:(?:текущем|открытом)\\s+)?чате\\s+$APP|in\\s+(?:the\\s+)?(?:current\\s+|open\\s+)?telegram\\s+chat)[.!?]?$", RegexOption.IGNORE_CASE)
    private val prefix = Regex("^(?:в\\s+(?:(?:текущем|открытом)\\s+)?чате\\s+$APP)[,:]?\\s+(?:напиши|напечатай|введи|отправь)\\s+(.+)$", RegexOption.IGNORE_CASE)
    fun request(input: String): Request? {
        val value = input.trim()
        val match = suffix.matchEntire(value) ?: prefix.matchEntire(value) ?: return null
        var text = match.groupValues[1].trim()
        val send = Regex("(?i)^(?:отправь|send)\\b").containsMatchIn(value) ||
            Regex("(?i)\\bотправь\\s+").containsMatchIn(value) && prefix.matches(value) ||
            Regex("(?i)\\s+(?:и\\s+)?(?:отправь|send)$").containsMatchIn(text)
        if (send) text = text.replace(Regex("(?i)\\s+(?:и\\s+)?(?:отправь|send)$"), "").trim()
        if (text.length >= 2 && ((text.first() == '«' && text.last() == '»') ||
                (text.first() == '"' && text.last() == '"'))) text = text.substring(1, text.length - 1)
        if (text.isBlank() || text.length > 1000 || text.any(Char::isISOControl)) return null
        return Request(text, send)
    }

    /** Telegram composer can lose its hint after typing; do not confuse it with search. */
    fun empty(node: GoalNode): Boolean = node.text.isNullOrBlank() ||
        (node.hint.isNotBlank() && node.text == node.hint)

    fun composer(nodes: List<GoalNode>): GoalNode? = nodes.singleOrNull {
        it.enabled && it.editable && listOf(it.hint, it.description).any { label ->
            label.trim().equals("Сообщение", true) || label.trim().equals("Message", true)
        }
    }
}
