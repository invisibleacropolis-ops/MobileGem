package com.mobilegem.gemma.inference

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.server.ChatMessage
import com.mobilegem.gemma.server.GemmaPromptBuilder
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_TOP_K = 64
private const val DEFAULT_TOP_P = 0.95
private const val DEFAULT_SEED = 0

/**
 * LiteRT-LM-backed text generator.
 *
 * Maintains a per-session [Conversation] cache: when [generateSession] is
 * invoked for the same `sessionId` with a strict prefix-extension of the
 * previous request, the existing Conversation is reused and only the new
 * user message is sent — preserving the KV cache. On any divergence
 * (history edited, regenerated, system message changed), the cached
 * Conversation for that session is closed and a fresh one is built from
 * the templated prompt.
 *
 * Falls back to the single-shot stateless [generate] path when there is
 * no session id.
 */
class LiteRtLmTextGenerator private constructor(
    private val engine: Engine,
) : SessionedTextGenerator, Closeable {

    private data class Slot(val conversation: Conversation, val sentMessages: List<ChatMessage>)

    private val sessionSlots = ConcurrentHashMap<String, Slot>()

    override fun generate(prompt: String, temperature: Float): Flow<String> = flow {
        AppLog.event(
            category = "engine",
            message = "generate.begin",
            "promptChars" to prompt.length,
            "temperature" to temperature,
        )
        val conversationConfig = sampledConversationConfig(temperature)
        var emittedChars = 0
        try {
            engine.createConversation(conversationConfig).use { conv ->
                conv.sendMessageAsync(prompt).collect { message ->
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

    override fun generateSession(
        sessionId: String?,
        systemContext: String?,
        messages: List<ChatMessage>,
        temperature: Float,
    ): Flow<String> = flow {
        if (sessionId == null) {
            val effective = if (systemContext == null) messages
            else listOf(ChatMessage("system", systemContext)) + messages
            val prompt = GemmaPromptBuilder.build(effective)
            generate(prompt, temperature).collect { emit(it) }
            return@flow
        }

        val fullMessages = if (systemContext == null) messages
        else listOf(ChatMessage("system", systemContext)) + messages

        val cached = sessionSlots[sessionId]
        val decision = ConversationCacheDecider.decide(
            previouslySent = cached?.sentMessages,
            incoming = fullMessages,
        )

        val (conv, textToSend) = when (decision) {
            is ConversationCacheDecider.Decision.Incremental -> {
                AppLog.event(
                    category = "engine",
                    message = "session.incremental",
                    "sessionId" to sessionId,
                    "history" to cached!!.sentMessages.size,
                )
                cached.conversation to decision.newUserMessage.content
            }
            is ConversationCacheDecider.Decision.Rebuild -> {
                AppLog.event(
                    category = "engine",
                    message = "session.rebuild",
                    "sessionId" to sessionId,
                    "messages" to decision.fullMessages.size,
                )
                cached?.conversation?.runCatching { close() }
                val prompt = GemmaPromptBuilder.build(decision.fullMessages)
                val newConv = engine.createConversation(sampledConversationConfig(temperature))
                newConv to prompt
            }
        }

        sessionSlots[sessionId] = Slot(conv, fullMessages)

        var emittedChars = 0
        try {
            conv.sendMessageAsync(textToSend).collect { message ->
                for (content in message.contents.contents) {
                    if (content is Content.Text) {
                        emittedChars += content.text.length
                        emit(content.text)
                    }
                }
            }
            AppLog.event(
                category = "engine",
                message = "session.end",
                "sessionId" to sessionId,
                "emittedChars" to emittedChars,
            )
        } catch (t: Throwable) {
            AppLog.error(
                category = "engine",
                message = "session.failed",
                throwable = t,
                "sessionId" to sessionId,
                "emittedChars" to emittedChars,
            )
            // Invalidate the slot on error so the next request rebuilds.
            sessionSlots.remove(sessionId)?.conversation?.runCatching { close() }
            throw t
        }
    }

    override fun close() {
        AppLog.event("engine", "close", "sessions" to sessionSlots.size)
        sessionSlots.values.forEach { it.conversation.runCatching { close() } }
        sessionSlots.clear()
        runCatching { engine.close() }
            .onFailure { AppLog.error("engine", "close.failed", it) }
    }

    private fun sampledConversationConfig(temperature: Float): ConversationConfig =
        ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = DEFAULT_TOP_K,
                topP = DEFAULT_TOP_P,
                temperature = temperature.toDouble(),
                seed = DEFAULT_SEED,
            ),
        )

    companion object {
        fun create(
            modelPath: String,
            backend: InferenceBackend,
            cacheDir: File? = null,
        ): LiteRtLmTextGenerator {
            val file = File(modelPath)
            AppLog.event(
                category = "engine",
                message = "create.begin",
                "modelPath" to modelPath,
                "modelExists" to file.exists(),
                "modelSizeBytes" to (if (file.exists()) file.length() else -1L),
                "backend" to backend.name,
                "cacheDir" to (cacheDir?.absolutePath ?: "<none>"),
            )

            cacheDir?.mkdirs()
            val engineConfig = if (cacheDir != null) {
                EngineConfig(
                    modelPath = modelPath,
                    backend = when (backend) {
                        InferenceBackend.GPU -> Backend.GPU()
                        InferenceBackend.CPU -> Backend.CPU()
                    },
                    cacheDir = cacheDir.absolutePath,
                )
            } else {
                EngineConfig(
                    modelPath = modelPath,
                    backend = when (backend) {
                        InferenceBackend.GPU -> Backend.GPU()
                        InferenceBackend.CPU -> Backend.CPU()
                    },
                )
            }
            return try {
                val engine = Engine(engineConfig)
                engine.initialize()
                AppLog.event("engine", "create.end", "backend" to backend.name)
                LiteRtLmTextGenerator(engine)
            } catch (t: Throwable) {
                AppLog.error("engine", "create.failed", t,
                    "modelPath" to modelPath, "backend" to backend.name)
                throw t
            }
        }
    }
}
