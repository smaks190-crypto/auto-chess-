package dev.nox.local

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.security.MessageDigest

internal data class ModelPackage(val name: String, val key: String, val repo: String,
    val revision: String, val filename: String, val size: Long, val sha256: String,
    val vision: Boolean, val cpuOnly: Boolean) {
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$filename"
}

internal object ModelDownload {
    // Immutable revisions and SHA-256 from the LiteRT community model cards.
    val catalog = listOf(
        ModelPackage("Gemma 4 E2B", "gemma-e2b", "litert-community/gemma-4-E2B-it-litert-lm",
            "6e5c4f1e395deb959c494953478fa5cec4b8008f", "gemma-4-E2B-it.litertlm",
            2588147712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", true, false),
        ModelPackage("Qwen 3.5 2B", "qwen-2b", "litert-community/Qwen3.5-2B",
            "895dd827bca539c532b359d9fec3b4fe512f8426", "Qwen3.5-2B_int8.litertlm",
            2119744416L, "fa4ac3259404567c7f82533746549eed94e2e1c8222095706c63cce17653c68a", false, true),
        ModelPackage("Qwen 3.5 4B", "qwen-4b", "litert-community/Qwen3.5-4B",
            "48fd119f895bd30634fcea417ba9c66b87575a38", "Qwen3.5-4B_int8.litertlm",
            4418863104L, "f04cff86142c0ce56b767f655ab41c20f41ce6c3373a053eb717939ec5e27242", false, true)
    )

    fun copyVerified(input: InputStream, output: OutputStream, size: Long, sha: String,
        cancelled: () -> Boolean, progress: (Long) -> Unit) {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            check(!cancelled()) { "Загрузка отменена" }
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= size) { "Размер файла больше ожидаемого" }
            output.write(buffer, 0, count)
            hash.update(buffer, 0, count)
            progress(total)
        }
        check(!cancelled()) { "Загрузка отменена" }
        check(total == size) { "Файл скачан не полностью" }
        val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == sha) { "Контрольная сумма файла не совпадает" }
    }

    fun download(pkg: ModelPackage, target: File, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        val parent = checkNotNull(target.parentFile)
        check(parent.usableSpace >= pkg.size + 256L * 1024 * 1024) { "Недостаточно свободного места" }
        val part = File(parent, target.name + ".part")
        val connection = URL(pkg.url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept-Encoding", "identity")
            check(connection.responseCode == 200) { "Ошибка скачивания HTTP ${connection.responseCode}" }
            val length = connection.contentLengthLong
            check(length < 0 || length == pkg.size) { "Размер файла на сервере не совпадает" }
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    copyVerified(input, output, pkg.size, pkg.sha256, cancelled, progress)
                    output.fd.sync()
                }
            }
            check(!cancelled()) { "Загрузка отменена" }
            java.nio.file.Files.move(part.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            connection.disconnect()
            part.delete()
        }
    }
}
