package dev.nox.local

import org.json.JSONObject

/** Validate the complete message, not coercing JSON types. */
internal data class ObsidianCommand(val op: String, val text: String = "", val model: Int = -1, val effort: Int = -1, val agent: Boolean = false, val enabled: Boolean = false, val voice: Boolean = false) {
    companion object {
        private val plain = setOf("ready", "cancel", "clear", "new_chat", "chat_history", "chat_older", "toggle_vision", "background", "bluetooth", "grants", "system_assistant", "search_settings", "overlay_permission", "accessibility_permission", "model_load", "model_import", "model_catalog", "model_cancel", "benchmark", "benchmark_log", "benchmark_share", "pick_image", "perception", "analyze", "remove_image", "review", "voice_open", "voice_close", "voice_start", "voice_stop")
        private val pages = setOf("chat", "actions", "settings")
        fun parse(raw: String): ObsidianCommand? {
            if (raw.length > 20000) return null
            return runCatching {
                val obj = JSONObject(raw)
                val op = obj.opt("op") as? String ?: return null
                val keys = obj.keys().asSequence().toSet()
                when {
                    op in plain && keys == setOf("op") -> ObsidianCommand(op)
                    op == "send" && (keys == setOf("op", "text", "agent") || keys == setOf("op", "text", "agent", "voice")) -> {
                        val text = (obj.opt("text") as? String)?.trim() ?: return null
                        val agent = obj.opt("agent") as? Boolean ?: return null
                        val voice = if ("voice" in keys) obj.opt("voice") as? Boolean ?: return null else false
                        if (text.isEmpty() || text.length > 2000) null else ObsidianCommand(op, text = text, agent = agent, voice = voice)
                    }
                    op == "choice" && keys == setOf("op", "model", "effort") -> {
                        val m = obj.opt("model") as? Int ?: return null
                        val e = obj.opt("effort") as? Int ?: return null
                        if (m !in 0..2 || e !in 0..3) null else ObsidianCommand(op, model = m, effort = e)
                    }
                    op == "model_select" && keys == setOf("op", "model") -> {
                        val model = obj.opt("model") as? Int ?: return null
                        if (model !in 0..2) null else ObsidianCommand(op, model = model)
                    }
                    op in setOf("model_gpu", "model_vision", "reduce_motion", "float_button") &&
                        keys == setOf("op", "enabled") -> {
                        val enabled = obj.opt("enabled") as? Boolean ?: return null
                        ObsidianCommand(op, enabled = enabled)
                    }
                    op == "copy" && keys == setOf("op", "text") -> {
                        val t = obj.opt("text") as? String ?: return null
                        if (t.length > 16000) null else ObsidianCommand(op, text = t)
                    }
                    op == "page" && keys == setOf("op", "page") -> {
                        val page = obj.opt("page") as? String ?: return null
                        if (page !in pages) null else ObsidianCommand(op, text = page)
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }
}
