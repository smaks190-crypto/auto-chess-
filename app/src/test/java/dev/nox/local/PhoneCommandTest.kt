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
    @Test fun separatesCompoundOpenFromFollowUp() {
        assertEquals(PhoneCommand.OpenWithFollowUp("telegram", "напиши александру привет"),
            PhoneCommands.parse("Открой Telegram и напиши Александру привет"))
        assertEquals(PhoneCommand.OpenWithFollowUp("телеграм", "отправь сообщение"),
            PhoneCommands.parse("Нокс, открой телеграм, а потом отправь сообщение"))
        assertEquals(PhoneCommand.Open("telegram"), PhoneCommands.parse("Нокс, Telegram открой"))
        assertNull(PhoneCommands.parse("Не открывай Telegram и не пиши Александру"))
        assertNull(PhoneCommands.parse("Как открыть Telegram и написать сообщение?"))
    }
    @Test fun bilingualSingleLaunchOnly() {
        for (phrase in listOf("Open Calculator", "Launch the Calculator", "Start Calculator", "Please open Calculator", "Nox, open Calculator!",
            "Открой калькулятор", "Запусти калькулятор", "Калькулятор открой", "Открой калькулятор пожалуйста", "Нокс, открой калькулятор")) {
            val expected = if (phrase.lowercase().any { it in 'а'..'я' }) "калькулятор" else "calculator"
            assertEquals(phrase, PhoneCommand.Open(expected), PhoneCommands.parse(phrase))
        }
        for (bad in listOf("Open Calculator and calculate 2+2", "Open Telegram then send a message", "Don't open Calculator",
            "How to open Calculator?", "Open Calculator without changing anything"))
            assertNull(bad, PhoneCommands.parse(bad))
    }
    @Test fun noAccidentalActions() {
        assertNull(PhoneCommands.parse("Не открывай Telegram"))
        assertNull(PhoneCommands.parse("Как открыть Telegram?"))
        assertNull(PhoneCommands.parse("Отправь письмо"))
        assertNull(PhoneCommands.parse("В статье написано: открой Telegram"))
    }
}
