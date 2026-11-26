package dev.nox.local

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Signal-level diagnostics, not a speech classifier. Does not retain PCM or transcripts. */
internal data class AudioMetrics(val samples: Long, val rmsDb: Double, val peak: Int,
    val clipped: Int, val silentMs: Long, val clockDeltaMs: Long, val firstTextMs: Long?, val lastWordEndMs: Long?)
internal class AudioDiag(private val rate: Int, private val startMs: Long) {
    init { require(rate > 0) }
    private var total = 0L
    private var quietSamples = 0L
    private var firstText: Long? = null
    private var lastWord: Long? = null
    fun recognized(nowMs: Long, nonempty: Boolean, wordEndMs: Long? = null) {
        if (nonempty && firstText == null) firstText = (nowMs - startMs).coerceAtLeast(0)
        if (wordEndMs != null && wordEndMs >= 0) lastWord = wordEndMs
    }
    fun frame(buf: ShortArray, count: Int, nowMs: Long): AudioMetrics {
        require(count in 1..buf.size)
        var sum = 0.0
        var peak = 0
        var clipped = 0
        for (i in 0 until count) {
            val v = abs(buf[i].toInt())
            sum += v.toDouble() * v
            peak = maxOf(peak, v)
            if (v >= 32767) clipped++
        }
        val rms = sqrt(sum / count)
        val db = if (rms < 1.0) -96.0 else 20 * log10(rms / 32768.0)
        total += count
        quietSamples = if (db <= -90) quietSamples + count else 0
        return AudioMetrics(total, db, peak, clipped, quietSamples * 1000 / rate,
            nowMs - startMs - total * 1000 / rate, firstText, lastWord)
    }
}
