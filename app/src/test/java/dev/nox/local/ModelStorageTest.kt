package dev.nox.local

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ModelStorageTest {
    @Test fun importDoesNotOverwriteCatalogOrOtherImports() {
        val dir = Files.createTempDirectory("model-test").toFile()
        try {
            val catalog = File(dir, "gemma.litertlm").apply { writeText("catalog") }
            val first = ModelStorage.importModel(dir, "gemma", "custom".byteInputStream()) { false }
            assertNotEquals(catalog, first)
            assertEquals("catalog", catalog.readText())
            assertEquals("custom", first.readText())
            assertTrue(ModelStorage.matches(first, first.length(), ModelStorage.sha256(first) { false }) { false })
            assertFalse(ModelStorage.matches(first, first.length(), "0".repeat(64)) { false })
            assertEquals(first, ModelStorage.importModel(dir, "gemma", "custom".byteInputStream()) { false })
            val second = ModelStorage.importModel(dir, "gemma", "other".byteInputStream()) { false }
            assertNotEquals(first, second)
            assertEquals("custom", first.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancellationRemovesOnlyTemporaryCopy() {
        val dir = Files.createTempDirectory("model-cancel").toFile()
        try {
            val catalog = File(dir, "gemma.litertlm").apply { writeText("catalog") }
            assertThrows(IllegalStateException::class.java) {
                ModelStorage.importModel(dir, "gemma", "cancel".byteInputStream()) { true }
            }
            assertEquals(listOf(catalog.name), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }
}
