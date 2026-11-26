package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class PhoneCommandTest {
    @Test fun exactOpen() {
        assertEquals(PhoneCommand.Open("telegram"), PhoneCommands.parse("Нокс, открой Telegram!"))
    }
    @Test fun directGreeting() {
        assertEquals(PhoneCommand.Greeting, PhoneCommands.parse("Здорово"))
    }
    @Test fun volume() {
        assertEquals(PhoneCommand.Volume(1), PhoneCommands.parse("Громче"))
        assertEquals(PhoneCommand.Volume(-1), PhoneCommands.parse("Тише"))
    }
    @Test fun navigation() {
        assertEquals(PhoneCommand.Home, PhoneCommands.parse("На главный экран"))
        assertEquals(PhoneCommand.Back, PhoneCommands.parse("Назад"))
    }
    @Test fun noAccidentalActions() {
        assertNull(PhoneCommands.parse("Не открывай Telegram"))
        assertNull(PhoneCommands.parse("Как открыть Telegram?"))
        assertNull(PhoneCommands.parse("Отправь письмо"))
        assertNull(PhoneCommands.parse("В статье написано: открой Telegram"))
    }
}
