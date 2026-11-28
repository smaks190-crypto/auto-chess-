package dev.nox.local

/** Explicit, read from actual UI, exact-label click. No guessed coordinates or contact. */
internal object ExplicitClick {
    data class Request(val app: String?, val label: String)
    private val command = Regex(
        "^(?:(?:открой|запусти)\\s+([\\p{L}\\p{N} ._-]{1,80}?)\\s+и\\s+)?нажми\\s+(?:кнопку\\s+)?(.+)$",
        RegexOption.IGNORE_CASE)
    private val compound = Regex("(?i)(?:^|\\s)(?:и|потом|затем|then|and)(?:\\s|$)")
    private fun norm(s: String) = s.trim().lowercase(java.util.Locale.ROOT)
    fun parse(input: String): Request? {
        val text = input.trim().replace(Regex("^(?:нокс|nox)[ ,!:.]+", RegexOption.IGNORE_CASE), "")
            .trim().trimEnd('.', '!', '?', ' ')
        val m = command.matchEntire(text) ?: return null
        val app = m.groupValues[1].trim().ifBlank { null }
        if (app != null && compound.containsMatchIn(app)) return null
        val raw = m.groupValues[2].trim()
        val quoted = raw.length >= 2 && ((raw.first() == '«' && raw.last() == '»') ||
            (raw.first() == '"' && raw.last() == '"'))
        val label = if (quoted) raw.substring(1, raw.length - 1) else raw
        if (label.isBlank() || label.length > 100 || label.any(Char::isISOControl)) return null
        if (!quoted && (!label.matches(Regex("[\\p{L}\\p{N} ._+\\-]{1,100}")) || compound.containsMatchIn(label))) return null
        if (label.any { it == '"' || it == '«' || it == '»' }) return null
        return Request(app, label)
    }
    fun node(request: Request, nodes: List<GoalNode>): GoalNode? =
        nodes.singleOrNull { n ->
            n.enabled && n.clickable && !n.editable && n.id.isNotBlank() &&
                listOfNotNull(n.description, n.text, n.label).any { norm(it) == norm(request.label) }
        }
}
