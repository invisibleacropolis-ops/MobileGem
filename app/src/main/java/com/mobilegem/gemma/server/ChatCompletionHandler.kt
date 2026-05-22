package com.mobilegem.gemma.server

import com.mobilegem.gemma.inference.TextGenerator
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
) {

    private val json = Json { encodeDefaults = true }

    /** Emits SSE payload strings, each already terminated with a blank line. */
    fun streamSse(request: ChatCompletionRequest): Flow<String> = flow {
        val id = "chatcmpl-${System.nanoTime()}"
        val created = System.currentTimeMillis() / 1000
        val temp = request.temperature ?: 0.8f
        val messages = augmentedMessages(request.messages)
        val prompt = GemmaPromptBuilder.build(messages)

        emit(sseChunk(id, created, request.model, Delta(role = "assistant"), null))
        val answer = StringBuilder()
        generator.generate(prompt, temp).collect { token ->
            answer.append(token)
            emit(sseChunk(id, created, request.model, Delta(content = token), null))
        }
        emit(sseChunk(id, created, request.model, Delta(), "stop"))
        emit("data: [DONE]\n\n")
        persist(request.messages, answer.toString())
    }

    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val temp = request.temperature ?: 0.8f
        val messages = augmentedMessages(request.messages)
        val prompt = GemmaPromptBuilder.build(messages)
        val answer = StringBuilder()
        generator.generate(prompt, temp).collect { answer.append(it) }
        persist(request.messages, answer.toString())
        return ChatCompletionResponse(
            id = "chatcmpl-${System.nanoTime()}",
            created = System.currentTimeMillis() / 1000,
            model = request.model,
            choices = listOf(MessageChoice(message = ChatMessage("assistant", answer.toString()))),
        )
    }

    /** Prepends a skills/memory system message when a session is active. */
    private suspend fun augmentedMessages(original: List<ChatMessage>): List<ChatMessage> {
        val session = activeSession?.current() ?: return original
        val aug = augmenter ?: return original
        val latestUser = original.lastOrNull { it.role == "user" }?.content ?: ""
        val context = aug.systemContextFor(session.projectId, latestUser) ?: return original
        return listOf(ChatMessage("system", context)) + original
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
