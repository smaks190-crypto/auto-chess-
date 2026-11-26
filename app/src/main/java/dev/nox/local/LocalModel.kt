package dev.nox.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/** All calls are owned by a single worker thread. No network client. */
internal class LocalModel : AutoCloseable {
    private var engine: Engine? = null

    fun load(file: File, gpu: Boolean) {
        require(file.isFile && file.length() > 0) { "Файл модели отсутствует" }
        close()
        val candidate = Engine(EngineConfig(
            modelPath = file.absolutePath,
            backend = if (gpu) Backend.GPU() else Backend.CPU(),
            visionBackend = Backend.GPU()
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
            if (image == null) conversation.sendMessage(prompt).text
            else conversation.sendMessage(Contents.of(
                Content.ImageFile(image.absolutePath), Content.Text(prompt)
            )).text
        }
    }

    override fun close() {
        val previous = engine
        engine = null
        previous?.close()
    }
}
