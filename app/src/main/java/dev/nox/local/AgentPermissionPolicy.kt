package dev.nox.local

internal enum class AppGrant { ONCE, ALWAYS, NEVER }

/** One instance per task; durable decisions are read on every access. */
internal class AgentPermissionPolicy(
    private val saved: (String) -> AppGrant?,
    private val persist: (String, AppGrant) -> Unit
) {
    private val once = mutableSetOf<String>()
    fun blocked(pkg: String) = saved(pkg) == AppGrant.NEVER
    fun allowed(pkg: String): Boolean = !blocked(pkg) &&
        (saved(pkg) == AppGrant.ALWAYS || pkg in once)
    fun apply(pkg: String, grant: AppGrant) {
        when (grant) {
            AppGrant.ONCE -> if (!blocked(pkg)) once.add(pkg) else Unit
            AppGrant.ALWAYS, AppGrant.NEVER -> { once.remove(pkg); persist(pkg, grant) }
        }
    }

    companion object {
        private const val CALC = "com.sec.android.app.popupcalculator"
        private val keys = (0..9).associate { "0$it" to it.toString() } +
            mapOf("add" to "+", "sub" to "−", "mul" to "×", "div" to "÷",
                "equal" to "=", "dot" to ",", "parenthesis" to "( )",
                "percentage" to "%", "clear" to "C")
        /** Unknown actions always require approval. Do not trust the model to classify risk. */
        fun safeAction(pkg: String, action: String, label: String, resource: String, kind: String): Boolean {
            if (pkg != CALC || action != "click" || kind != "Button") return false
            val prefix = "$CALC:id/calc_keypad_btn_"
            if (!resource.startsWith(prefix)) return false
            val expected = keys[resource.removePrefix(prefix)] ?: return false
            return label.substringBefore(" / ").trim() == expected
        }
    }
}
