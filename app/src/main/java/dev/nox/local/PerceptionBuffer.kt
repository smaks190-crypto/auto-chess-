package dev.nox.local

internal data class PerceptionSnapshot(val frame: ByteArray?, val speech: String, val frameAt: Long)

/** One newest frame; speech is a bounded rolling context, never a command. */
internal class PerceptionBuffer {
    private var pixels: ByteArray? = null
    private var timestamp = 0L
    private var committed = ""
    private var pending = ""
    @Synchronized fun frame(jpeg: ByteArray, now: Long) {
        pixels = jpeg.copyOf()
        timestamp = now
    }
    @Synchronized fun partial(text: String) { pending = text.takeLast(600) }
    @Synchronized fun finalText(text: String) {
        committed = (committed + " " + text.trim()).trim().takeLast(1800)
        pending = ""
    }
    @Synchronized fun snapshot(now: Long): PerceptionSnapshot =
        PerceptionSnapshot(pixels?.takeIf { now - timestamp in 0..3000 }?.copyOf(),
            (committed + " " + pending).trim().takeLast(1800), timestamp)
    @Synchronized fun clear() { pixels = null; timestamp = 0L; committed = ""; pending = "" }
}
