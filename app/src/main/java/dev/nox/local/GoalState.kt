package dev.nox.local

import java.math.BigInteger

/** Independent, deliberately narrow verifier. Unknown goals remain unverified. */
internal data class GoalNode(val resource: String, val label: String)
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
        for ((word, digit) in numberWords) s = s.replace(Regex("\\b" + word + "\\b"), digit)
        s.replace(Regex("\\bплюс\\b"), "+").replace(Regex("\\bминус\\b"), "-")
    }
    private val match = expression.findAll(normalized).toList().singleOrNull()
        ?.takeIf { normalized.contains("calculator") || normalized.contains("калькулятор") }
    private val formula = match?.let { it.groupValues[1] + it.groupValues[2].replace('−','-') + it.groupValues[3] }
    private val expected = match?.let {
        val a = BigInteger(it.groupValues[1]); val b = BigInteger(it.groupValues[3])
        (if (it.groupValues[2] == "+") a + b else a - b).toString()
    }
    private var verifiedEqualsSeq: Long? = null
    private var lastMutation = -1L
    private var nextSeq = 0L
    private var launchName: String? = null
    private var launchPkg: String? = null
    fun sequence() = ++nextSeq
    fun launched(pkg: String, label: String) { launchPkg = pkg; launchName = label }
    fun mutation(): Long { verifiedEqualsSeq = null; return sequence().also { lastMutation = it } }
    private fun value(o: GoalObservation): String? {
        if (o.pkg != CALC) return null
        val raw = o.nodes.singleOrNull { it.resource == FORMULA }?.label ?: return null
        val v = raw.substringBefore(" / ").removePrefix("Поле ввода в калькуляторе ").removePrefix("Calculator input field ")
            .replace(" ", "").replace("\u00a0", "").replace("\u202f", "").replace('−','-')
        return v.takeIf { it.matches(Regex("[0-9+\\-]+")) }
    }
    private fun wantedKey(o: GoalObservation): String? {
        val f = formula ?: return null
        val v = value(o) ?: return null
        if (v == f) return "equal"
        val prefix = if (v == "0" && !f.startsWith("0")) "" else v
        if (!f.startsWith(prefix) || prefix.length >= f.length) return "clear"
        return when (val ch = f[prefix.length]) { '+' -> "add"; '-' -> "sub"; else -> "0" + ch }
    }
    fun hint(o: GoalObservation): String? = wantedKey(o)?.let { key ->
        "Проверка цели: требуется кнопка " + KEY + key + ". Выбери её ID из текущего экрана."
    }
    fun reject(o: GoalObservation, action: String, resource: String): String? {
        if (formula == null || o.pkg != CALC) return null
        if (verifiedEqualsSeq != null) return "Вычисление уже подтверждено; заверши задачу."
        val key = wantedKey(o) ?: return "Не удалось прочитать поле формулы; обнови экран."
        return if (action == "click" && resource == KEY + key) null
            else "Выбранное действие не соответствует выражению. " + hint(o)
    }
    fun afterClick(before: GoalObservation, resource: String, after: GoalObservation) {
        if (resource == KEY + "equal" && value(before) == formula && value(after) == expected && after.seq > lastMutation)
            verifiedEqualsSeq = after.seq
    }
    fun finish(o: GoalObservation?): String? {
        if (o == null || o.seq <= lastMutation) return null
        if (formula != null) {
            if (verifiedEqualsSeq != null && o.seq >= verifiedEqualsSeq!! && value(o) == expected)
                return "В калькуляторе подтверждено: " + formula + " = " + expected + "."
            return null
        }
        val target = Regex("^(?:открой|запусти|open|launch)\\s+([\\p{L}0-9 ._-]+)$", RegexOption.IGNORE_CASE)
            .matchEntire(request.trim())?.groupValues?.get(1)?.trim() ?: return null
        if (o.pkg == launchPkg && (target.equals(launchName, true) || target.equals(launchPkg, true)))
            return "Приложение открыто: " + launchName
        return null
    }
}
