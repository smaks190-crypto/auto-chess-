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
            require(answer.isNotBlank() && answer.length <= 3000)
            return AgentDecision.Finish(answer)
        }
        val name = string("tool")
        require(keys == if (name == "type") setOf("tool", "arg", "text") else setOf("tool", "arg")) {
            "Некорректные параметры инструмента"
        }
        val arg = string("arg")
        val text = if (name == "type") string("text") else ""
        when (name) {
            "apps" -> require(arg.length <= 100)
            "launch" -> require(arg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")))
            "observe", "home", "back" -> require(arg.isEmpty())
            "click", "type" -> require(arg.matches(Regex("[1-9][0-9]{0,2}")))
            "scroll", "volume" -> require(arg == "up" || arg == "down")
            else -> error("Неизвестный инструмент: " + name)
        }
        require(text.length <= 1000)
        return AgentDecision.Tool(name, arg, text)
    }
}
