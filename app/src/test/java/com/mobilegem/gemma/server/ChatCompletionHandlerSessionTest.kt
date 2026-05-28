package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeSessionedTextGenerator
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.ConversationPersister
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatCompletionHandlerSessionTest {

    private fun augmenterReturning(text: String?): ContextAugmenter =
        object : ContextAugmenter {
            override suspend fun systemContextFor(projectId: Long, latestUserMessage: String) = text
        }

    @Test
    fun routesThroughGenerateSessionWhenSessionActiveAndGeneratorSessioned() = runTest {
        val gen = FakeSessionedTextGenerator(listOf("answer"))
        val holder = ActiveSessionHolder().apply { set(projectId = 1, sessionId = 42) }
        val handler = ChatCompletionHandler(
            generator = gen,
            augmenter = augmenterReturning("Active skills: be terse"),
            persister = null,
            activeSession = holder,
        )
        handler.streamSse(
            ChatCompletionRequest(
                messages = listOf(ChatMessage("user", "hi")),
                stream = true,
            ),
        ).toList()

        assertThat(gen.calls).hasSize(1)
        val call = gen.calls.single()
        assertThat(call.sessionId).isEqualTo("42")
        assertThat(call.systemContext).contains("be terse")
        assertThat(call.messages.map { it.role to it.content })
            .containsExactly("user" to "hi")
    }

    @Test
    fun usesStatelessGenerateWhenNoSessionActive() = runTest {
        val gen = FakeSessionedTextGenerator(listOf("ok"))
        val handler = ChatCompletionHandler(
            generator = gen,
            augmenter = augmenterReturning("ignored"),
            persister = null,
            activeSession = ActiveSessionHolder(),
        )
        handler.streamSse(
            ChatCompletionRequest(
                messages = listOf(ChatMessage("user", "q")),
                stream = true,
            ),
        ).toList()

        assertThat(gen.calls).hasSize(1)
        val call = gen.calls.single()
        // Stateless fallback recorded as session=null.
        assertThat(call.sessionId).isNull()
    }
}
