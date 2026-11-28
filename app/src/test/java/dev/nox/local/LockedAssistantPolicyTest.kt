package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class LockedAssistantPolicyTest {
    @Test fun onlyExplicitInteractiveKeyguardSession() {
        assertTrue(LockedAssistantPolicy.sessionAllowed(true,true,true,true,true))
        assertFalse(LockedAssistantPolicy.sessionAllowed(false,true,true,true,true))
        assertFalse(LockedAssistantPolicy.sessionAllowed(true,false,true,true,true))
        assertFalse(LockedAssistantPolicy.sessionAllowed(true,true,false,true,true))
        assertFalse(LockedAssistantPolicy.sessionAllowed(true,true,true,false,false))
        assertFalse(LockedAssistantPolicy.sessionAllowed(true,true,true,null,true))
    }
    @Test fun protectedCommandsAndPublicSearchRemainSeparate() {
        assertTrue(LockedAssistantPolicy.needsUnlock("Нокс, открой Telegram"))
        assertTrue(LockedAssistantPolicy.needsUnlock("прочитай сообщения"))
        assertTrue(LockedAssistantPolicy.needsUnlock("разблокируй телефон"))
        assertTrue(LockedAssistantPolicy.publicSearch("погугли погоду"))
        assertTrue(LockedAssistantPolicy.cancelTimerRequested("Нокс, отмени таймер"))
        assertFalse(LockedAssistantPolicy.cancelTimerRequested("отмени таймер и открой Telegram"))
        assertFalse(LockedAssistantPolicy.needsUnlock("расскажи анекдот"))
    }
    @Test fun exactBoundedTimerDuration() {
        assertEquals(60, LockedAssistantPolicy.timerSeconds("Нокс, поставь таймер на минуту"))
        assertEquals(300, LockedAssistantPolicy.timerSeconds("поставь таймер на пять минут"))
        assertEquals(90, LockedAssistantPolicy.timerSeconds("установи таймер на 1 минуту 30 секунд"))
        assertEquals(3600, LockedAssistantPolicy.timerSeconds("запусти таймер на час"))
        for (bad in listOf("поставь таймер", "поставь таймер на 0 секунд", "поставь таймер на 2 часа",
            "поставь таймер на минуту и открой Telegram", "не ставь таймер на минуту", "поставь таймер на 1 минуту 2 минуты"))
            assertNull(bad, LockedAssistantPolicy.timerSeconds(bad))
    }
}
