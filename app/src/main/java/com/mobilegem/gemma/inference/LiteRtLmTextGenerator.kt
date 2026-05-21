package com.mobilegem.gemma.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable

/**
 * Owns a single LiteRT-LM [Engine] for one model file. Construct via [create];
 * call [close] when switching models or shutting down.
 */
class LiteRtLmTextGenerator private constructor(
    private val engine: Engine,
) : TextGenerator, Closeable {

    override fun generate(prompt: String, temperature: Float): Flow<String> = flow {
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = DEFAULT_TOP_K,
                topP = DEFAULT_TOP_P,
                temperature = temperature.toDouble(),
                seed = DEFAULT_SEED,
            ),
        )
        engine.createConversation(config).use { conversation ->
            conversation.sendMessageAsync(prompt).collect { message ->
                for (content in message.contents.contents) {
                    if (content is Content.Text) emit(content.text)
                }
            }
        }
    }

    override fun close() {
        engine.close()
    }

    companion object {
        private const val DEFAULT_TOP_K = 64
        private const val DEFAULT_TOP_P = 0.95
        private const val DEFAULT_SEED = 0

        fun create(modelPath: String, backend: InferenceBackend): LiteRtLmTextGenerator {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = when (backend) {
                    InferenceBackend.GPU -> Backend.GPU()
                    InferenceBackend.CPU -> Backend.CPU()
                },
            )
            val engine = Engine(engineConfig)
            engine.initialize()
            return LiteRtLmTextGenerator(engine)
        }
    }
}
