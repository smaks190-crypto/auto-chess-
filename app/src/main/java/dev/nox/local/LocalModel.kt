package dev.nox.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ThinkingConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/** All calls are owned by a single worker thread. No network client. */
internal class LocalModel : AutoCloseable {
    private var engine: Engine? = null

    fun load(file: File, gpu: Boolean, vision: Boolean = true) {
        require(file.isFile && file.length() > 0) { "Файл модели отсутствует" }
        close()
        val candidate = Engine(EngineConfig(
            modelPath = file.absolutePath,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            visionBackend = if (vision) Backend.GPU() else null
        ))
        try {
            candidate.initialize()
            engine = candidate
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw error
        }
    }

    fun analyze(command: String, image: File?): String {
        val prompt = PromptPolicy.build(command)
        val active = checkNotNull(engine) { "Сначала загрузите модель" }
        return active.createConversation().use { conversation ->
            if (image == null) conversation.sendMessage(prompt).toString()
            else conversation.sendMessage(Contents.of(
                Content.ImageFile(image.absolutePath), Content.Text(prompt)
            )).toString()
        }
    }

    fun chat(command: String, image: File?, deep: Boolean, cancelled: () -> Boolean, update: (String) -> Unit): String {
        val prompt = PromptPolicy.build(command) + "\nОтвечай естественно, кратко и по существу. Не перечисляй свои возможности без запроса."
        return generate(prompt, image, deep, cancelled, update)
    }

    fun decide(prompt: String, cancelled: () -> Boolean): AgentDecision {
        val raw = generate(prompt, null, false, cancelled, {})
        return try {
            AgentProtocol.parse(raw)
        } catch (e: RuntimeException) {
            // Retry only schema/JSON errors, never model failures or cancelled generation.
            throw AgentFormatException(e.message ?: "Неверный JSON ответа модели")
        }
    }

    private fun generate(prompt: String, image: File?, deep: Boolean, cancelled: () -> Boolean,
        update: (String) -> Unit): String {
        val active = checkNotNull(engine) { "Сначала загрузите модель" }
        return active.createConversation().use { conversation ->
            val done = CountDownLatch(1)
            val output = StringBuilder()
            val failure = AtomicReference<Throwable?>(null)
            val accepting = AtomicBoolean(true)
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    synchronized(output) {
                        if (accepting.get() && !cancelled()) {
                            output.append(message.toString())
                            update(output.toString())
                        }
                    }
                }
                override fun onDone() { accepting.set(false); done.countDown() }
                override fun onError(throwable: Throwable) {
                    failure.set(throwable); accepting.set(false); done.countDown()
                }
            }
            val contents = if (image == null) Contents.of(Content.Text(prompt))
                else Contents.of(Content.ImageFile(image.absolutePath), Content.Text(prompt))
            if (cancelled()) throw CancellationException("Генерация отменена")
            try {
                conversation.sendMessageAsync(contents, callback,
                    maxOutputToken = if (deep) 2048 else 512,
                    thinkingConfig = ThinkingConfig(enableThinking = deep,
                        thinkingTokenBudget = if (deep) 1024 else 0))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (deep) 300 else 120)
                while (!done.await(200, TimeUnit.MILLISECONDS)) {
                    if (cancelled() || System.nanoTime() >= deadline) {
                        accepting.set(false)
                        runCatching { conversation.cancelProcess() }
                        // Allow native cancellation to deliver its terminal callback before closing.
                        done.await(5, TimeUnit.SECONDS)
                        if (cancelled()) throw CancellationException("Генерация отменена")
                        error("Истекло время генерации")
                    }
                }
                if (cancelled()) throw CancellationException("Генерация отменена")
                failure.get()?.let { throw it }
                synchronized(output) { output.toString() }
            } finally {
                accepting.set(false)
            }
        }
    }

    override fun close() {
        val previous = engine
        engine = null
        previous?.close()
    }
}
