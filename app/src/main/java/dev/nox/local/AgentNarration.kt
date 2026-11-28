package dev.nox.local

import java.util.Locale

/** Describes resolved actions, never guesses a completed outcome from model output. */
internal object AgentNarration {
    private fun clean(value: String, limit: Int = 160): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").trim().let {
            if (it.length > limit) it.take(limit) + "…" else it
        }
    private fun words(value: String): String = value.lowercase(Locale.ROOT).trim()
    private fun isSearch(label: String, resource: String): Boolean =
        words(label) in setOf("поиск", "искать", "найти", "search", "search messages", "поиск сообщений", "поиск чатов", "search chats") ||
            resource.substringAfterLast('/').lowercase(Locale.ROOT).contains("search")
    private fun isMessage(label: String, resource: String): Boolean =
        words(label) in setOf("сообщение", "message", "написать сообщение", "type a message", "введите сообщение", "write a message") ||
            resource.substringAfterLast('/').lowercase(Locale.ROOT) in setOf("message_input", "message_edit_text", "chat_input", "compose_text")
    private fun isSend(label: String): Boolean =
        words(label) in setOf("отправить", "send", "отправить сообщение", "send message")

    fun launch(app: String) = "Хорошо, открываю " + clean(app, 100) + "."
    fun click(label: String, resource: String): String = when {
        isSend(label) -> "Готовлю отправку, жду твоего подтверждения."
        words(label.substringBefore(" / ")) in setOf("удалить", "удаление", "delete", "remove", "удалить навсегда", "delete permanently") ->
            "Готовлю удаление, жду твоего подтверждения."
        isSearch(label, resource) -> "Открываю поиск."
        label.isNotBlank() -> "Перехожу к «" + clean(label, 120) + "»."
        else -> "Нажимаю на элемент без названия."
    }
    fun type(label: String, resource: String, text: String): String = when {
        isSearch(label, resource) -> "Ищу «" + clean(text) + "»."
        isMessage(label, resource) -> "Пишу сообщение: «" + clean(text) + "»."
        else -> "Ввожу «" + clean(text) + "»."
    }
}
