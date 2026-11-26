package dev.nox.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.CountDownLatch
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

    fun chat(command: String, image: File?, update: (String) -> Unit): String {
        val prompt = PromptPolicy.build(command) + "\nОтвечай естественно, кратко и по существу. Не перечисляй свои возможности без запроса."
        val active = checkNotNull(engine) { "Сначала загрузите модель" }
        return active.createConversation().use { conversation ->
            val done = CountDownLatch(1)
            val output = StringBuilder()
            var failure: Throwable? = null
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    output.append(message.toString())
                    update(output.toString())
                }
                override fun onDone() { done.countDown() }
                override fun onError(throwable: Throwable) { failure = throwable; done.countDown() }
            }
            val contents = if (image == null) Contents.of(Content.Text(prompt))
                else Contents.of(Content.ImageFile(image.absolutePath), Content.Text(prompt))
            conversation.sendMessageAsync(contents, callback, maxOutputToken = 512)
            done.await()
            failure?.let { throw it }
            output.toString()
        }
    }

    override fun close() {
        val previous = engine
        engine = null
        previous?.close()
    }
}
