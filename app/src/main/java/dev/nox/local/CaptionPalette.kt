package dev.nox.local

internal enum class CaptionPalette(val fill: Int, val text: Int) {
    DARK(0xFF15121D.toInt(), 0xFFFFFFFF.toInt()),
    LIGHT(0xFFF8F5FF.toInt(), 0xFF15121D.toInt());

    companion object {
        fun forWallpaper(argb: Int?): CaptionPalette {
            if (argb == null) return DARK
            fun channel(shift: Int): Double {
                val x = ((argb ushr shift) and 255) / 255.0
                return if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
            }
            val luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
            return if (luminance >= 0.179) DARK else LIGHT
        }
    }
}
