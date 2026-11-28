package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class ExtraToolsTest {
    @Test fun remindersAreExplicitAndBounded() {
        assertEquals(ExtraCommand.Reminder(600, null, "выключить чайник"), ExtraTools.parse("Нокс, напомни через десять минут выключить чайник"))
        assertEquals(ExtraCommand.Reminder(null, NativeCommand.Alarm(8,30,emptySet(),true), "взять ключи"), ExtraTools.parse("напомни завтра в 8:30 взять ключи"))
        assertEquals(ExtraCommand.ReminderInvalid, ExtraTools.parse("напомни через 0 секунд позвонить"))
        assertEquals(ExtraCommand.CancelReminder(3), ExtraTools.parse("отмени напоминание номер 3"))
        assertNull(ExtraTools.parse("не напомни через минуту"))
    }
    @Test fun explicitPublicRequestsOnly() {
        assertEquals(ExtraCommand.WeatherApp, ExtraTools.parse("открой погоду"))
        assertEquals(ExtraCommand.Weather("Москва",true), ExtraTools.parse("погода завтра в Москва"))
        assertEquals(ExtraCommand.Weather("",false), ExtraTools.parse("какая погода"))
        assertEquals(ExtraCommand.Search("Android",false), ExtraTools.parse("Нокс, найди в интернете Android"))
        assertEquals(ExtraCommand.Search("Android",true), ExtraTools.parse("найди в Википедии Android"))
        assertNull(ExtraTools.parse("не ищи в интернете Android"))
    }
}
