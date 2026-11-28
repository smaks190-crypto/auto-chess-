package dev.nox.local

import android.os.SystemClock
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Diagnostic only: no download, persistent cache, chat history, tools, or backend switch. */
internal object NanoProbe {
    internal fun statusLabel(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE — модель доступна"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE — модель можно скачать; загрузка не начата"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING — модель уже загружается системой"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE — публичный доступ к модели недоступен"
        else -> "Неизвестный статус: " + status
    }

    suspend fun run(allowed: () -> Boolean): String {
        if (!allowed()) return "Проверка отменена."
        val report = mutableListOf<String>()
        try {
            withTimeout(60_000L) {
                val model = Generation.getClient()
                try {
                    val status = model.checkStatus()
                    report += statusLabel(status)
                    if (status != FeatureStatus.AVAILABLE) return@withTimeout
                    if (!allowed()) return@withTimeout
                    report += "Модель: " + model.getBaseModelName()
                    report += "Лимит контекста (вход + выход): " + model.getTokenLimit()
                    for ((label, prompt) in listOf(
                        "Арифметика" to "Ответь только числом: 3600 - 450 - 700.",
                        "Русский язык" to "Напиши ровно одно короткое предложение о зиме на русском языке."
                    )) {
                        if (!allowed()) return@withTimeout
                        val start = SystemClock.elapsedRealtime()
                        val response = model.generateContent(generateContentRequest(TextPart(prompt)) {
                            temperature = 0.1f
                            maxOutputTokens = 64
                        })
                        val elapsed = SystemClock.elapsedRealtime() - start
                        val answer = response.candidates.firstOrNull()?.text.orEmpty().trim()
                        report += "$label: ${elapsed} мс\n" + if (answer.isEmpty()) "Пустой ответ" else answer.take(500)
                    }
                } finally {
                    model.close()
                }
            }
        } catch (_: TimeoutCancellationException) {
            report += "Превышен общий лимит проверки 60 с."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only fixed synthetic requests; avoid exposing provider internals or user data.
            report += "Ошибка ML Kit: " + e.javaClass.simpleName
            val message = e.message.orEmpty()
            Regex("(?i)(?:error (?:type|code) \\d+[A-Z_-]*|[0-9]{3}-[A-Z_]+)").findAll(message).take(3).forEach { report += it.value }
        }
        val result = report.joinToString("\n\n")
        android.util.Log.i("NoxNanoProbe", result)
        return result
    }
}
