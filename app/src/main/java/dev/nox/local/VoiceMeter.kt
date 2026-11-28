package dev.nox.local

import kotlin.math.log10

/** Visual-only PCM meter; never changes samples passed to ASR. */
internal object VoiceMeter {
    fun level(pcm: ShortArray, count: Int): Float {
        val n = count.coerceIn(0, pcm.size)
        if (n == 0) return 0f
        var energy = 0.0
        for (i in 0 until n) {
            val sample = pcm[i] / 32768.0
            energy += sample * sample
        }
        if (energy == 0.0) return 0f
        // 10 log10(mean square) == 20 log10(RMS). Speech uses the full display range.
        val db = 10.0 * log10(energy / n)
        return ((db + 60.0) / 40.0).toFloat().coerceIn(0f, 1f)
    }
    fun follow(previous: Float, target: Float): Float {
        val p = previous.coerceIn(0f, 1f)
        val t = target.coerceIn(0f, 1f)
        return p + (t - p) * if (t > p) .82f else .30f
    }
}
