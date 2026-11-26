package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AgentPermissionPolicyTest {
    @Test fun grantsAreScopedToPackageAndTask() {
        val saved = mutableMapOf<String, AppGrant>()
        fun session() = AgentPermissionPolicy({ saved[it] }, { pkg, grant -> saved[pkg] = grant })
        val first = session()
        first.apply("calculator", AppGrant.ONCE)
        assertTrue(first.allowed("calculator"))
        assertFalse(first.allowed("telegram"))
        assertFalse(session().allowed("calculator"))
        first.apply("telegram", AppGrant.ALWAYS)
        assertTrue(session().allowed("telegram"))
        session().apply("telegram", AppGrant.NEVER)
        assertFalse(first.allowed("telegram"))
        assertTrue(session().blocked("telegram"))
    }
    @Test fun persistentDenyCannotBeOverriddenByTaskGrant() {
        val saved = mutableMapOf("calculator" to AppGrant.NEVER)
        val policy = AgentPermissionPolicy({ saved[it] }, { pkg, grant -> saved[pkg] = grant })
        policy.apply("calculator", AppGrant.ONCE)
        assertFalse(policy.allowed("calculator"))
    }
    @Test fun onlyKnownCalculatorKeysCanSkipActionConfirmation() {
        val pkg = "com.sec.android.app.popupcalculator"
        assertTrue(AgentPermissionPolicy.safeAction(pkg, "click", "3 / 3", "com.sec.android.app.popupcalculator:id/calc_keypad_btn_03", "Button"))
        assertTrue(AgentPermissionPolicy.safeAction(pkg, "click", "+ / Плюс", "com.sec.android.app.popupcalculator:id/calc_keypad_btn_add", "Button"))
        assertFalse(AgentPermissionPolicy.safeAction(pkg, "click", "Удалить", "com.sec.android.app.popupcalculator:id/delete", "Button"))
        assertFalse(AgentPermissionPolicy.safeAction("org.telegram.messenger", "click", "3", "id/3", "Button"))
        assertFalse(AgentPermissionPolicy.safeAction(pkg, "type", "3", "", "EditText"))
        assertFalse(AgentPermissionPolicy.safeAction(pkg, "click", "3", "", "Button"))
    }
    @Test fun telegramSendAndUnknownControlsAlwaysAsk() {
        val pkg = "org.telegram.messenger.web"
        assertFalse(AgentPermissionPolicy.safeAction(pkg, "click", "Отправить", "", "ImageButton"))
        assertFalse(AgentPermissionPolicy.safeAction(pkg, "click", "Перевести", "", "Button"))
        assertFalse(AgentPermissionPolicy.safeAction(pkg, "click", "Александр", "", "View"))
        assertFalse(AgentPermissionPolicy.safeAction(pkg, "type", "Сообщение", "", "EditText"))
    }
}
