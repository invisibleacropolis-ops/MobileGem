package com.mobilegem.gemma.server

import com.mobilegem.gemma.inference.SessionedTextGenerator
import com.mobilegem.gemma.inference.TextGenerator
import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.memory.ActiveSession
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.ConversationPersister
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class ChatCompletionHandler(
    private val generator: TextGenerator,
    private val augmenter: ContextAugmenter? = null,
    private val persister: ConversationPersister? = null,
    private val activeSession: ActiveSessionHolder? = null,
    /** Total context window in tokens (model-dependent). Gemma 4 defaults to 8192. */
    private val contextWindow: Int = 8192,
    /** Reserved budget for the model's own output. */
    private val outputBudget: Int = 1024,
) {

    private val json = Json { encodeDefaults = true }
    private val maxInputTokens: Int get() = contextWindow - outputBudget

    /** Emits SSE payload strings, each already terminated with a blank line. */
    fun streamSse(request: ChatCompletionRequest): Flow<String> = flow {
        AppLog.event(
            "chat", "chat.handle.begin",
            "messageCount" to request.messages.size, "stream" to true,
        )
        val id = "chatcmpl-${System.nanoTime()}"
        val created = System.currentTimeMillis() / 1000
        val temp = request.temperature ?: 0.8f

        emit(sseChunk(id, created, request.model, Delta(role = "assistant"), null))
        val answer = StringBuilder()
        try {
            runGeneration(request.messages, temp).collect { token ->
                answer.append(token)
                emit(sseChunk(id, created, request.model, Delta(content = token), null))
            }
            emit(sseChunk(id, created, request.model, Delta(), "stop"))
            emit("data: [DONE]\n\n")
            persist(request.messages, answer.toString())
            AppLog.event("chat", "chat.handle.end", "assistantChars" to answer.length)
        } catch (t: Throwable) {
            AppLog.error("chat", "chat.handle.failed", t)
            throw t
        }
    }

    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        AppLog.event(
            "chat", "chat.handle.begin",
            "messageCount" to request.messages.size, "stream" to false,
        )
        val temp = request.temperature ?: 0.8f
        val answer = StringBuilder()
        try {
            runGeneration(request.messages, temp).collect { answer.append(it) }
            persist(request.messages, answer.toString())
            AppLog.event("chat", "chat.handle.end", "assistantChars" to answer.length)
        } catch (t: Throwable) {
            AppLog.error("chat", "chat.handle.failed", t)
            throw t
        }
        return ChatCompletionResponse(
            id = "chatcmpl-${System.nanoTime()}",
            created = System.currentTimeMillis() / 1000,
            model = request.model,
            choices = listOf(MessageChoice(message = ChatMessage("assistant", answer.toString()))),
        )
    }

    /**
     * Single dispatch site: when an active session exists AND the generator
     * implements [SessionedTextGenerator], hand off [systemContext] and
     * [messages] separately so the implementation can reuse a per-session
     * KV cache. Otherwise, collapse the system context into the messages
     * list and run the stateless prompt-building path.
     */
    private fun runGeneration(
        original: List<ChatMessage>, temperature: Float,
    ): Flow<String> = flow {
        val session = activeSession?.current()
        val systemContext = buildSystemContext(session, original)
        val hasSession = session != null

        if (session != null && generator is SessionedTextGenerator) {
            val bounded = MessageBudget.fitWithinBudget(original, maxInputTokens)
            AppLog.event(
                "chat", "chat.augment",
                "hasSession" to hasSession,
                "hasAugmenter" to (augmenter != null),
                "injectedChars" to (systemContext?.length ?: 0),
                "boundedMessages" to bounded.size,
                "sessioned" to true,
            )
            generator.generateSession(
                sessionId = session.sessionId.toString(),
                systemContext = systemContext,
                messages = bounded,
                temperature = temperature,
            ).collect { emit(it) }
        } else {
            val augmented = if (systemContext == null) original
            else listOf(ChatMessage("system", systemContext)) + original
            val bounded = MessageBudget.fitWithinBudget(augmented, maxInputTokens)
            AppLog.event(
                "chat", "chat.augment",
                "hasSession" to hasSession,
                "hasAugmenter" to (augmenter != null),
                "injectedChars" to (systemContext?.length ?: 0),
                "boundedMessages" to bounded.size,
                "sessioned" to false,
            )
            val prompt = GemmaPromptBuilder.build(bounded)
            generator.generate(prompt, temperature).collect { emit(it) }
        }
    }

    /** Resolves the per-request system context, or null if none should be injected. */
    private suspend fun buildSystemContext(
        session: ActiveSession?, original: List<ChatMessage>,
    ): String? {
        if (session == null) return null
        val aug = augmenter ?: return null
        val latestUser = original.lastOrNull { it.role == "user" }?.content ?: ""
        return aug.systemContextFor(session.projectId, latestUser)
    }

    /** Replaces the active session's stored transcript with the latest exchange. */
    private suspend fun persist(original: List<ChatMessage>, answer: String) {
        val session = activeSession?.current() ?: return
        val p = persister ?: return
        p.persistConversation(session.sessionId, original + ChatMessage("assistant", answer))
    }

    private fun sseChunk(
        id: String, created: Long, model: String, delta: Delta, finish: String?,
    ): String {
        val chunk = ChatCompletionChunk(
            id = id, created = created, model = model,
            choices = listOf(ChunkChoice(delta = delta, finishReason = finish)),
        )
        return "data: ${json.encodeToString(ChatCompletionChunk.serializer(), chunk)}\n\n"
    }
}
