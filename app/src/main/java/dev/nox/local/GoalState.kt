package dev.nox.local

import java.math.BigInteger

/** Independent, deliberately narrow verifier. Unknown goals remain unverified. */
internal data class GoalNode(val resource: String, val label: String,
    val id: String = "", val hint: String = "", val description: String = "", val kind: String = "",
    val enabled: Boolean = true, val clickable: Boolean = false, val editable: Boolean = false)
internal data class GoalObservation(val seq: Long, val pkg: String, val nodes: List<GoalNode>)
internal class GoalState(private val request: String) {
    companion object {
        private const val CALC = "com.sec.android.app.popupcalculator"
        private const val FORMULA = "$CALC:id/calc_edt_formula"
        private const val KEY = "$CALC:id/calc_keypad_btn_"
        private val expression = Regex("(?<![0-9.,-])([0-9]{1,12})\\s*([+−-])\\s*([0-9]{1,12})(?![0-9.,])")
        private val numberWords = mapOf("ноль" to "0", "один" to "1", "два" to "2", "три" to "3", "четыре" to "4", "пять" to "5", "шесть" to "6", "семь" to "7", "восемь" to "8", "девять" to "9")
    }
    private val normalized = request.lowercase().let { original ->
        var s = original
        fun wordPattern(word: String) = Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(word) + "(?![\\p{L}\\p{N}_])")
        for ((word, digit) in numberWords) s = s.replace(wordPattern(word), digit)
        s.replace(wordPattern("плюс"), "+").replace(wordPattern("минус"), "-")
    }
    private val match = expression.findAll(normalized).toList().singleOrNull()
        ?.takeIf { m ->
            val remainder = normalized.removeRange(m.range)
            (normalized.contains("calculator") || normalized.contains("калькулятор")) &&
                remainder.none { it.isDigit() || it in "+−-*/×÷=" } &&
                m.groupValues[1].let { it == "0" || !it.startsWith("0") } &&
                m.groupValues[3].let { it == "0" || !it.startsWith("0") }
        }
    private val formula = match?.let { it.groupValues[1] + it.groupValues[2].replace('−','-') + it.groupValues[3] }
    private val expected = match?.let {
        val a = BigInteger(it.groupValues[1]); val b = BigInteger(it.groupValues[3])
        (if (it.groupValues[2] == "+") a + b else a - b).toString()
    }
    // Only unambiguous, explicitly requested calculator jobs use the shortcut.
    // Other requests continue through the normal model/executor path.
    private val fastArithmetic = formula != null && run {
        val expr = "[0-9]{1,12}\\s*[+−-]\\s*[0-9]{1,12}"
        val en = "(?:open (?:the )?calculator and calculate\\s+$expr(?:\\s+using (?:the )?buttons)?|calculate\\s+$expr\\s+(?:in|using) (?:the )?calculator)"
        val ru = "(?:(?:открой|запусти) калькулятор и (?:посчитай|вычисли|рассчитай|сложи)\\s+$expr(?:\\s+(?:кнопками|с помощью кнопок))?|(?:посчитай|вычисли|рассчитай)\\s+$expr\\s+в калькуляторе)"
        Regex("(?:$en|$ru)[.!]?").matches(normalized.trim())
    }
    private val openTarget = Regex("^(?:открой|запусти|open|launch)\\s+([\\p{L}0-9 ._-]+?)[.!?]?\\s*$", RegexOption.IGNORE_CASE)
        .matchEntire(request.trim())?.groupValues?.get(1)?.trim()
    val searchTarget = SearchNavigation.target(request)
    val searchNavigation: Boolean get() = searchTarget != null
    val supported: Boolean get() = formula != null || openTarget != null || searchNavigation
    fun matchesSearchApp(pkg: String, label: String): Boolean =
        searchTarget?.let { SearchNavigation.matchesApp(it, label, pkg) } ?: true
    val arithmetic: Boolean get() = formula != null
    fun allowsLaunch(pkg: String) = !arithmetic || pkg == CALC
    private var verifiedEqualsSeq: Long? = null
    private var lastMutation = -1L
    private var nextSeq = 0L
    private var launchName: String? = null
    private var launchPkg: String? = null
    fun sequence() = ++nextSeq
    fun launched(pkg: String, label: String) { launchPkg = pkg; launchName = label }
    fun searchScreen(o: GoalObservation): Boolean = searchNavigation && o.pkg == launchPkg &&
        matchesSearchApp(o.pkg, launchName.orEmpty())
    fun searchNext(o: GoalObservation): String? =
        if (searchScreen(o) && o.seq > lastMutation) SearchNavigation.next(o.nodes) else null
    fun searchAfter(before: GoalObservation, after: GoalObservation): Boolean =
        searchScreen(before) && searchScreen(after) && after.seq > lastMutation && after.seq > before.seq &&
            SearchNavigation.advanced(before.nodes, after.nodes)
    fun mutation(): Long { verifiedEqualsSeq = null; return sequence().also { lastMutation = it } }
    private fun value(o: GoalObservation): String? {
        if (o.pkg != CALC) return null
        val raw = o.nodes.singleOrNull { it.resource == FORMULA }?.label ?: return null
        val field = raw.substringBefore(" / ").trim()
        val resultSuffix = Regex("\\s+(?:Результат вычисления|Calculation result|Result of calculation)$", RegexOption.IGNORE_CASE)
        val hasResultSuffix = resultSuffix.containsMatchIn(field)
        val v = field.replace(resultSuffix, "")
            .replace(Regex("^(?:Поле ввода в калькуляторе|Calculator input field)(?=\\s|$)", RegexOption.IGNORE_CASE), "")
            // Samsung speaks operator names in its Accessibility EditText.
            // Replace whole words only; never discard arbitrary unrecognized text.
            .replace(Regex("(?<!\\p{L})(?:плюс|plus)(?!\\p{L})", RegexOption.IGNORE_CASE), "+")
            .replace(Regex("(?<!\\p{L})(?:минус|minus)(?!\\p{L})", RegexOption.IGNORE_CASE), "-")
            .replace(" ", "").replace("\u00a0", "").replace("\u202f", "").replace('−','-')
        return v.takeIf {
            it.matches(Regex(if (hasResultSuffix) "-?[0-9]+" else "[0-9+\\-]*"))
        }
    }
    private fun wantedKey(o: GoalObservation): String? {
        val f = formula ?: return null
        val v = value(o) ?: return null
        if (v == f) return "equal"
        val prefix = if (v == "0" && !f.startsWith("0")) "" else v
        if (!f.startsWith(prefix) || prefix.length >= f.length) return "clear"
        return when (val ch = f[prefix.length]) { '+' -> "add"; '-' -> "sub"; else -> "0" + ch }
    }
    // Only an exact, already-recognized arithmetic task can authorize a batch.
    // A non-prefix display (including a prior result) is handled by the ordinary
    // single-action path; clearing the screen is not implicitly approved.
    fun approvedSequence(o: GoalObservation): List<String>? {
        if (!fastArithmetic || verifiedEqualsSeq != null || o.seq <= lastMutation) return null
        val f = formula ?: return null
        val v = value(o) ?: return null
        val prefix = if (v == "0" && !f.startsWith("0")) "" else v
        if (!f.startsWith(prefix)) return null
        return f.drop(prefix.length).map { ch ->
            KEY + when (ch) { '+' -> "add"; '-' -> "sub"; else -> "0" + ch }
        } + (KEY + "equal")
    }

    fun fastResource(o: GoalObservation): String? {
        if (!fastArithmetic || verifiedEqualsSeq != null || o.seq <= lastMutation) return null
        return wantedKey(o)?.let { KEY + it }
    }
    fun fastFinish(o: GoalObservation): Boolean = fastArithmetic && finish(o) != null

    fun hint(o: GoalObservation): String? = if (verifiedEqualsSeq != null) "Вычисление проверено. Заверши задачу." else wantedKey(o)?.let { key ->
        "Проверка цели: требуется кнопка " + KEY + key + ". Выбери её ID из текущего экрана."
    }
    fun reject(o: GoalObservation, action: String, resource: String): String? {
        if (formula == null || o.pkg != CALC) return null
        if (verifiedEqualsSeq != null) return "Вычисление уже подтверждено; заверши задачу."
        val key = wantedKey(o) ?: return "Не удалось прочитать поле формулы; обнови экран."
        return if (action == "click" && resource == KEY + key) null
            else "Выбранное действие не соответствует выражению. " + hint(o)
    }
    fun afterClick(before: GoalObservation, resource: String, after: GoalObservation): Boolean {
        if (!arithmetic) return true
        val previous = value(before) ?: return false
        val actual = value(after) ?: return false
        if (after.seq <= lastMutation || after.seq <= before.seq || resource != KEY + wantedKey(before)) return false
        val next = when (val key = resource.removePrefix(KEY)) {
            "clear" -> "0"
            "equal" -> expected
            "add" -> previous + "+"
            "sub" -> previous + "-"
            else -> (if (previous == "0" && formula?.startsWith("0") == false) "" else previous) + key.removePrefix("0")
        }
        if (resource == KEY + "clear") {
            if (actual != "" && actual != "0") return false
        } else if (actual != next) return false
        if (resource == KEY + "equal" && previous == formula) verifiedEqualsSeq = after.seq
        return true
    }
    fun finish(o: GoalObservation?): String? {
        if (o == null || o.seq <= lastMutation) return null
        if (formula != null) {
            if (verifiedEqualsSeq != null && o.seq >= verifiedEqualsSeq!! && value(o) == expected)
                return "В калькуляторе подтверждено: " + formula + " = " + expected + "."
            return null
        }
        if (searchNavigation) {
            return if (searchScreen(o) && SearchNavigation.state(o.nodes) == SearchNavigation.State.SEARCH)
                "В приложении " + launchName + " подтверждено открытие поиска. Сообщения не отправлял."
            else null
        }
        val target = openTarget ?: return null
        if (o.pkg == launchPkg && (SearchNavigation.matchesApp(target, launchName.orEmpty(), launchPkg.orEmpty()) || (o.pkg == CALC && target.equals("calculator", true))))
            return "Приложение открыто: " + launchName
        return null
    }
}
