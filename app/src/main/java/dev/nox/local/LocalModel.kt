package dev.nox.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ThinkingConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import android.os.SystemClock
import android.util.Log

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

    fun classifyRequest(prompt: String, cancelled: () -> Boolean): String =
        generate(prompt, null, false, cancelled, {}, maxOutputTokens = 24)

    /** Keep native conversation state only for a single agent task. Chat, routing and images
     * still use independent conversations. All native calls remain on the existing worker. */
    fun <R> withAgentPlanner(cancelled: () -> Boolean, shortDecisions: Boolean,
                             block: ((String) -> AgentDecision) -> R): R {
        val active = checkNotNull(engine) { "Сначала загрузите модель" }
        val planner = PlannerSession<Conversation, AgentDecision>(
            create = {
                val started = SystemClock.elapsedRealtime()
                active.createConversation().also {
                    Log.i("NoxPlan", "session=create elapsedMs=${SystemClock.elapsedRealtime() - started}")
                }
            },
            generate = { conversation, prompt, warm ->
                val started = SystemClock.elapsedRealtime()
                val limit = if (shortDecisions) 160 else 512
                val raw = try {
                    generateInConversation(conversation, prompt, null, false, cancelled, {},
                        maxOutputTokens = limit, trace = true)
                } finally {
                    Log.i("NoxPlan", "source=MODEL warm=$warm elapsedMs=${SystemClock.elapsedRealtime() - started} promptChars=${prompt.length} outputLimit=$limit")
                }
                try {
                    AgentProtocol.parse(raw)
                } catch (e: Exception) {
                    // PlannerSession drops the conversation before the existing JSON-only retry.
                    throw AgentFormatException(e.message ?: "Неверный JSON ответа модели")
                }
            },
            cancelled = cancelled
        )
        return planner.use { block(it::next) }
    }

    private fun generate(prompt: String, image: File?, deep: Boolean, cancelled: () -> Boolean,
        update: (String) -> Unit, maxOutputTokens: Int = if (deep) 2048 else 512): String {
        val active = checkNotNull(engine) { "Сначала загрузите модель" }
        return active.createConversation().use { conversation ->
            generateInConversation(conversation, prompt, image, deep, cancelled, update, maxOutputTokens)
        }
    }

    private fun generateInConversation(conversation: Conversation, prompt: String, image: File?,
        deep: Boolean, cancelled: () -> Boolean, update: (String) -> Unit,
        maxOutputTokens: Int, trace: Boolean = false): String {
            val started = SystemClock.elapsedRealtime()
            val firstChunk = AtomicLong(-1L)
            val done = CountDownLatch(1)
            val output = StringBuilder()
            val failure = AtomicReference<Throwable?>(null)
            val accepting = AtomicBoolean(true)
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    synchronized(output) {
                        if (accepting.get() && !cancelled()) {
                            val chunk = message.toString()
                            if (chunk.isNotEmpty()) firstChunk.compareAndSet(-1L, SystemClock.elapsedRealtime() - started)
                            output.append(chunk)
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
            return try {
                conversation.sendMessageAsync(contents, callback,
                    maxOutputToken = maxOutputTokens,
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
            } catch (e: InterruptedException) {
                accepting.set(false)
                runCatching { conversation.cancelProcess() }
                Thread.currentThread().interrupt()
                throw CancellationException("Генерация прервана")
            } finally {
                accepting.set(false)
                if (trace) Log.i("NoxPlan", "firstChunkMs=${firstChunk.get()} totalMs=${SystemClock.elapsedRealtime() - started}")
            }
    }

    override fun close() {
        val previous = engine
        engine = null
        previous?.close()
    }
}
