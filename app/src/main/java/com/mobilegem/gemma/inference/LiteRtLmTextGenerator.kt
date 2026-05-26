package com.mobilegem.gemma.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.io.File

private const val DEFAULT_TOP_K = 64
private const val DEFAULT_TOP_P = 0.95
private const val DEFAULT_SEED = 0

/**
 * Owns a single LiteRT-LM [Engine] for one model file. Construct via [create];
 * call [close] when switching models or shutting down.
 */
class LiteRtLmTextGenerator private constructor(
    private val engine: Engine,
) : TextGenerator, Closeable {

    override fun generate(prompt: String, temperature: Float): Flow<String> = flow {
        AppLog.event(
            "engine", "generate.begin",
            "promptChars" to prompt.length, "temperature" to temperature,
        )
        val conversationConfig = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = DEFAULT_TOP_K,
                topP = DEFAULT_TOP_P,
                temperature = temperature.toDouble(),
                seed = DEFAULT_SEED,
            ),
        )
        var emittedChars = 0
        try {
            engine.createConversation(conversationConfig).use { conversation ->
                conversation.sendMessageAsync(prompt).collect { message ->
                    for (content in message.contents.contents) {
                        if (content is Content.Text) {
                            emittedChars += content.text.length
                            emit(content.text)
                        }
                    }
                }
            }
            AppLog.event("engine", "generate.end", "emittedChars" to emittedChars)
        } catch (t: Throwable) {
            AppLog.error("engine", "generate.failed", t, "emittedChars" to emittedChars)
            throw t
        }
    }

    override fun close() {
        AppLog.event("engine", "close")
        runCatching { engine.close() }
            .onFailure { AppLog.error("engine", "close.failed", it) }
    }

    companion object {
        /**
         * @param cacheDir Writable directory the LiteRT-LM runtime can use for
         * compilation artifacts. Strongly recommended on Android; many runtime
         * versions fail with an opaque INTERNAL error otherwise.
         */
        fun create(
            modelPath: String,
            backend: InferenceBackend,
            cacheDir: File? = null,
        ): LiteRtLmTextGenerator {
            val file = File(modelPath)
            AppLog.event(
                "engine", "create.begin",
                "modelPath" to modelPath,
                "modelExists" to file.exists(),
                "modelSizeBytes" to (if (file.exists()) file.length() else -1L),
                "backend" to backend.name,
                "cacheDir" to (cacheDir?.absolutePath ?: "<none>"),
            )

            cacheDir?.mkdirs()
            val backendInstance = when (backend) {
                InferenceBackend.GPU -> Backend.GPU()
                InferenceBackend.CPU -> Backend.CPU()
            }
            val engineConfig = if (cacheDir != null) {
                EngineConfig(
                    modelPath = modelPath,
                    backend = backendInstance,
                    cacheDir = cacheDir.absolutePath,
                )
            } else {
                EngineConfig(
                    modelPath = modelPath,
                    backend = backendInstance,
                )
            }
            return try {
                val engine = Engine(engineConfig)
                engine.initialize()
                AppLog.event("engine", "create.end", "backend" to backend.name)
                LiteRtLmTextGenerator(engine)
            } catch (t: Throwable) {
                AppLog.error(
                    "engine", "create.failed", t,
                    "modelPath" to modelPath, "backend" to backend.name,
                )
                throw t
            }
        }
    }
}
