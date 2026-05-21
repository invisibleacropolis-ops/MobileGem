package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class OpenAiDtoTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun parsesAChatCompletionRequest() {
        val raw = """
            {"model":"gemma","stream":true,
             "messages":[{"role":"user","content":"hi"}]}
        """.trimIndent()
        val req = json.decodeFromString<ChatCompletionRequest>(raw)
        assertThat(req.stream).isTrue()
        assertThat(req.messages).hasSize(1)
        assertThat(req.messages[0].role).isEqualTo("user")
        assertThat(req.messages[0].content).isEqualTo("hi")
    }

    @Test
    fun serializesAStreamChunk() {
        val chunk = ChatCompletionChunk(
            id = "abc",
            created = 1L,
            model = "gemma",
            choices = listOf(ChunkChoice(delta = Delta(content = "tok"), finishReason = null)),
        )
        val out = json.encodeToString(ChatCompletionChunk.serializer(), chunk)
        assertThat(out).contains("\"object\":\"chat.completion.chunk\"")
        assertThat(out).contains("\"content\":\"tok\"")
    }
}
