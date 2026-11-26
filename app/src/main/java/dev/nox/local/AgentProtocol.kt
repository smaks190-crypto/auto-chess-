package dev.nox.local

import org.json.JSONObject
import org.json.JSONTokener

internal object AgentProtocol {
    fun parse(raw: String): AgentDecision {
        val trimmed = raw.trim()
        val body = if (trimmed.startsWith("```json\n") && trimmed.endsWith("```"))
            trimmed.removePrefix("```json\n").removeSuffix("```").trim() else trimmed
        require(body.length <= 5000) { "Слишком длинный ответ агента" }
        val tokener = JSONTokener(body)
        val obj = tokener.nextValue() as? JSONObject ?: error("Ожидался JSON-объект")
        require(tokener.nextClean() == '\u0000') { "Лишний текст после JSON" }
        val keys = obj.keys().asSequence().toSet()
        fun string(key: String): String = obj.opt(key) as? String ?: error("Отсутствует строка " + key)
        if (keys == setOf("finish")) {
            val answer = string("finish")
            require(answer.isNotBlank() && answer.length <= 3000) { "finish: ожидается непустой ответ длиной до 3000 символов" }
            return AgentDecision.Finish(answer)
        }
        val name = string("tool")
        val noArg = name in setOf("observe", "home", "back")
        val expected = if (name == "type") setOf("tool", "arg", "text") else setOf("tool", "arg")
        require(keys == expected || (noArg && keys == setOf("tool"))) {
            "Некорректный набор полей: ожидаются " + expected.joinToString(", ")
        }
        val arg = when {
            noArg && !obj.has("arg") -> ""
            name in setOf("click", "type") && obj.opt("arg") is Number -> obj.opt("arg").toString()
            else -> string("arg")
        }
        val text = if (name == "type") string("text") else ""
        when (name) {
            "apps" -> require(arg.length <= 100) { "apps: поисковая строка длиннее 100 символов" }
            "launch" -> require(arg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { "launch: нужен точный пакет из apps" }
            "observe", "home", "back" -> require(arg.isEmpty()) { "Для этого инструмента arg должен быть пустым" }
            "click", "type" -> require(arg.matches(Regex("[1-9][0-9]{0,2}"))) { "ID узла должен быть целым числом от 1 до 999" }
            "scroll", "volume" -> require(arg == "up" || arg == "down") { "arg должен быть up или down" }
            else -> error("Неизвестный инструмент: " + name)
        }
        require(text.length <= 1000) { "text: больше 1000 символов" }
        return AgentDecision.Tool(name, arg, text)
    }
}
