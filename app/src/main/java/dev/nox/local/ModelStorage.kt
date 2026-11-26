package dev.nox.local

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object ModelStorage {
    private fun digestHex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    fun sha256(file: File, cancelled: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                check(!cancelled()) { "Операция отменена" }
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        check(!cancelled()) { "Операция отменена" }
        return digestHex(digest)
    }
    fun matches(file: File, size: Long, hash: String, cancelled: () -> Boolean): Boolean =
        file.isFile && file.length() == size && sha256(file, cancelled) == hash

    fun importModel(dir: File, key: String, input: InputStream, cancelled: () -> Boolean): File {
        require(key.matches(Regex("[a-z0-9-]+")))
        val temp = File.createTempFile("$key-import-", ".part", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    check(!cancelled()) { "Импорт отменён" }
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                }
                output.fd.sync()
            }
            check(!cancelled()) { "Импорт отменён" }
            require(temp.length() > 0) { "Пустой файл" }
            val hash = digestHex(digest)
            val target = File(dir, "$key.imported.$hash.litertlm")
            if (target.exists()) {
                check(matches(target, temp.length(), hash, cancelled)) { "Существующий импорт повреждён; файл сохранён" }
            } else {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }
            return target
        } finally { temp.delete() }
    }
}
