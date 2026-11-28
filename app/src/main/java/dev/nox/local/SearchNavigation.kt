package dev.nox.local

/** Semantic controls, not screen coordinates or Telegram-specific resource IDs.
 * Unknown or ambiguous UI is not authorization to guess. */
internal object SearchNavigation {
    enum class State { SEARCH, LIST, COMPOSER, UNKNOWN }
    private val names = setOf("поиск", "поиск чатов", "search", "search chats")
    private val composer = setOf("сообщение", "message", "написать сообщение", "write a message")
    private val back = setOf("назад", "back")
    private fun norm(s: String) = s.trim().lowercase(java.util.Locale.ROOT)
    private fun field(n: GoalNode, names: Set<String>) =
        n.enabled && n.editable && (norm(n.hint) in names || norm(n.description) in names)
    private fun control(n: GoalNode, names: Set<String>) =
        n.enabled && n.clickable && !n.editable && n.kind in setOf("ImageButton", "ImageView", "Button") &&
            norm(n.description) in names
    data class Request(val app: String, val query: String? = null)

    fun request(input: String): Request? {
        val value = input.trim()
        val openEn = Regex("^(?:open|launch)\\s+([\\p{L}0-9 ._-]{1,80}?)\\s+and\\s+(?:open|show)\\s+(?:the\\s+)?search[.!]?(?:\\s+do not send anything[.!]?)?$", RegexOption.IGNORE_CASE)
        val openRu = Regex("^(?:открой|запусти)\\s+([\\p{L}0-9 ._-]{1,80}?)\\s+и\\s+(?:открой|покажи)\\s+поиск[.!]?(?:\\s+ничего не отправляй[.!]?)?$", RegexOption.IGNORE_CASE)
        val openMatch = openEn.matchEntire(value) ?: openRu.matchEntire(value)
        if (openMatch != null) return Request(openMatch.groupValues[1].trim())

        val queryEn = Regex("^open\\s+([\\p{L}0-9 ._-]{1,80}?)\\s+and\\s+type\\s+(.+?)\\s+in\\s+search[.!]?$", RegexOption.IGNORE_CASE)
        val queryRu = Regex("^открой\\s+([\\p{L}0-9 ._-]{1,80}?)\\s+и\\s+(?:напиши|введи)\\s+в\\s+поиск(?:е)?\\s+(.+?)[.!]?$", RegexOption.IGNORE_CASE)
        val queryMatch = queryEn.matchEntire(value) ?: queryRu.matchEntire(value)
        if (queryMatch != null) {
            val app = queryMatch.groupValues[1]
            val query = queryMatch.groupValues[2]
            val parsedQuery = when {
                query.startsWith('"') && query.endsWith('"') && query.length >= 2 &&
                    '"' !in query.substring(1, query.length - 1) -> query.substring(1, query.length - 1)
                query.matches(Regex("[\\p{L}0-9_-]{1,80}")) -> query
                else -> return null
            }
            if (parsedQuery.isBlank() || parsedQuery.length > 80 || parsedQuery.any(Char::isISOControl)) return null
            return Request(app, parsedQuery)
        }
        return null
    }

    fun target(request: String): String? = request(request)?.app
    fun matchesApp(target: String, label: String, pkg: String): Boolean {
        val t = norm(target)
        val l = norm(label)
        return t == l || t == norm(pkg) ||
            (t in setOf("telegram", "телеграм", "тг") && l in setOf("telegram", "телеграм")) ||
            (t in setOf("calculator", "калькулятор") && l in setOf("calculator", "калькулятор"))
    }
    fun state(nodes: List<GoalNode>): State {
        val searchFields = nodes.count { field(it, names) }
        val composers = nodes.count { field(it, composer) }
        if (searchFields == 1 && composers == 0) return State.SEARCH
        if (searchFields != 0 || composers > 1) return State.UNKNOWN
        if (composers == 1 && nodes.count { control(it, back) } == 1) return State.COMPOSER
        if (composers == 0 && nodes.count { control(it, names) } == 1) return State.LIST
        return State.UNKNOWN
    }
    /** Input requires an unambiguous search field; never a message composer. */
    fun inputField(nodes: List<GoalNode>): GoalNode? =
        if (state(nodes) == State.SEARCH && nodes.count { it.enabled && it.editable } == 1)
            nodes.singleOrNull { field(it, names) }?.takeIf { it.id.isNotBlank() }
        else null

    fun next(nodes: List<GoalNode>): String? = when (state(nodes)) {
        State.COMPOSER -> nodes.single { control(it, back) }.id.takeIf { it.isNotBlank() }
        State.LIST -> nodes.single { control(it, names) }.id.takeIf { it.isNotBlank() }
        else -> null
    }
    fun advanced(before: List<GoalNode>, after: List<GoalNode>): Boolean = when (state(before)) {
        State.COMPOSER -> state(after) == State.LIST || state(after) == State.SEARCH
        State.LIST -> state(after) == State.SEARCH
        else -> false
    }
}
