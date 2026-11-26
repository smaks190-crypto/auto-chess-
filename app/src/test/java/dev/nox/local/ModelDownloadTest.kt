package dev.nox.local

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ModelDownloadTest {
    private val bytes = "test model".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    @Test fun correctPayload() {
        val output = ByteArrayOutputStream()
        ModelDownload.copyVerified(ByteArrayInputStream(bytes), output, bytes.size.toLong(), digest, { false }) {}
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test fun rejectsWrongDigest() {
        assertThrows(IllegalStateException::class.java) {
            ModelDownload.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), bytes.size.toLong(), "0".repeat(64), { false }) {}
        }
    }
    @Test fun rejectsTruncation() {
        assertThrows(IllegalStateException::class.java) {
            ModelDownload.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), bytes.size.toLong()+1, digest, { false }) {}
        }
    }
    @Test fun rejectsOversize() {
        assertThrows(IllegalStateException::class.java) {
            ModelDownload.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), 1, digest, { false }) {}
        }
    }
    @Test fun cancellation() {
        assertThrows(IllegalStateException::class.java) {
            ModelDownload.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), bytes.size.toLong(), digest, { true }) {}
        }
    }
}
