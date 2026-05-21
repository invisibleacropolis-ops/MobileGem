package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeTextGenerator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatCompletionHandlerTest {

    @Test
    fun streamingProducesSseChunksEndingWithDone() = runTest {
        val gen = FakeTextGenerator(tokens = listOf("Hi", "!"))
        val handler = ChatCompletionHandler(gen)
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage("user", "hello")),
            stream = true,
        )

        val events = handler.streamSse(request).toList()

        // role chunk, two content chunks, finish chunk, [DONE]
        assertThat(events).hasSize(5)
        assertThat(events[0]).contains("\"role\":\"assistant\"")
        assertThat(events[1]).contains("\"content\":\"Hi\"")
        assertThat(events[2]).contains("\"content\":\"!\"")
        assertThat(events[3]).contains("\"finish_reason\":\"stop\"")
        assertThat(events[4]).isEqualTo("data: [DONE]\n\n")
        assertThat(gen.lastPrompt).contains("<start_of_turn>user\nhello")
    }

    @Test
    fun nonStreamingAggregatesIntoSingleResponse() = runTest {
        val gen = FakeTextGenerator(tokens = listOf("Full ", "answer"))
        val handler = ChatCompletionHandler(gen)
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage("user", "q")),
            stream = false,
        )

        val response = handler.complete(request)

        assertThat(response.choices).hasSize(1)
        assertThat(response.choices[0].message.content).isEqualTo("Full answer")
        assertThat(response.choices[0].message.role).isEqualTo("assistant")
    }
}
