package dev.nox.local

internal enum class ChatEffort(val label: String) {
    AUTO("Авто"), FAST("Быстро"), STANDARD("Стандарт"), DEEP("Глубоко");
    fun budget(command: String, image: Boolean): Int = when (this) {
        AUTO -> if (image || command.length > 120) 256 else 0
        FAST -> 0
        STANDARD -> 256
        DEEP -> 1024
    }
}

internal data class MatrixChoice(val model: Int, val effort: ChatEffort) {
    companion object {
        fun at(x: Float, y: Float, width: Float, height: Float, rows: Int): MatrixChoice? {
            if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0 || height <= 0 || rows <= 0 || x < 0 || y < 0 || x > width || y > height) return null
            val levels = ChatEffort.entries
            return MatrixChoice((y / height * rows).toInt().coerceIn(0, rows - 1), levels[(x / width * levels.size).toInt().coerceIn(levels.indices)])
        }
    }
}
