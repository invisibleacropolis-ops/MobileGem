package com.mobilegem.gemma.server

import com.mobilegem.gemma.inference.TextGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class ChatCompletionHandler(private val generator: TextGenerator) {

    private val json = Json { encodeDefaults = true }

    /** Emits SSE payload strings, each already terminated with a blank line. */
    fun streamSse(request: ChatCompletionRequest): Flow<String> = flow {
        val id = "chatcmpl-${System.nanoTime()}"
        val created = System.currentTimeMillis() / 1000
        val temp = request.temperature ?: 0.8f
        val prompt = GemmaPromptBuilder.build(request.messages)

        emit(sseChunk(id, created, request.model, Delta(role = "assistant"), null))
        generator.generate(prompt, temp).collect { token ->
            emit(sseChunk(id, created, request.model, Delta(content = token), null))
        }
        emit(sseChunk(id, created, request.model, Delta(), "stop"))
        emit("data: [DONE]\n\n")
    }

    suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val temp = request.temperature ?: 0.8f
        val prompt = GemmaPromptBuilder.build(request.messages)
        val sb = StringBuilder()
        generator.generate(prompt, temp).collect { sb.append(it) }
        return ChatCompletionResponse(
            id = "chatcmpl-${System.nanoTime()}",
            created = System.currentTimeMillis() / 1000,
            model = request.model,
            choices = listOf(
                MessageChoice(message = ChatMessage("assistant", sb.toString())),
            ),
        )
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
