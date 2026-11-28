package dev.nox.local

import org.junit.Assert.*
import org.junit.Test

class PerceptionBufferTest {
    @Test fun latestFrameReplacesPreviousAndStaleFramesExpire() {
        val buffer = PerceptionBuffer()
        buffer.frame(byteArrayOf(1), 1000)
        buffer.frame(byteArrayOf(2), 1200)
        assertArrayEquals(byteArrayOf(2), buffer.snapshot(1300).frame)
        assertNull(buffer.snapshot(5000).frame)
    }
    @Test fun partialSpeechDoesNotDuplicateAndTextIsBounded() {
        val buffer = PerceptionBuffer()
        buffer.partial("привет")
        buffer.partial("привет мир")
        buffer.finalText("привет мир")
        assertEquals("привет мир", buffer.snapshot(1000).speech)
        repeat(100) { buffer.finalText("x".repeat(100)) }
        assertTrue(buffer.snapshot(1000).speech.length <= 1800)
    }
    @Test fun clearRemovesCapturedContent() {
        val buffer = PerceptionBuffer()
        buffer.frame(byteArrayOf(7), 1000)
        buffer.finalText("speech")
        buffer.clear()
        assertNull(buffer.snapshot(1000).frame)
        assertEquals("", buffer.snapshot(1000).speech)
    }
}
