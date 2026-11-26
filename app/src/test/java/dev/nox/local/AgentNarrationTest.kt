package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class AgentNarrationTest {
    @Test fun launchUsesResolvedApplicationName() {
        assertEquals("Хорошо, открываю Telegram.", AgentNarration.launch("Telegram"))
    }
    @Test fun searchAndChatInputHaveDifferentCommentary() {
        assertEquals("Ищу «Александр».", AgentNarration.type("Поиск", "", "Александр"))
        assertEquals("Пишу сообщение: «Привет».", AgentNarration.type("Сообщение", "", "Привет"))
        assertEquals("Ввожу «42».", AgentNarration.type("Номер", "", "42"))
    }
    @Test fun searchControlAndSendAreNotReportedAsCompleted() {
        assertEquals("Открываю поиск.", AgentNarration.click("Search", ""))
        assertEquals("Готовлю отправку, жду твоего подтверждения.", AgentNarration.click("Отправить", ""))
        assertEquals("Перехожу к «Александр».", AgentNarration.click("Александр", ""))
    }
    @Test fun destructiveControlIsDescribedAsPending() {
        assertEquals("Готовлю удаление, жду твоего подтверждения.", AgentNarration.click("Удалить", ""))
        assertEquals("Готовлю удаление, жду твоего подтверждения.", AgentNarration.click("Delete", ""))
    }
    @Test fun unknownControlIsDescribedHonestly() {
        assertEquals("Нажимаю на элемент без названия.", AgentNarration.click("", ""))
    }
}
