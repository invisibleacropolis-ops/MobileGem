package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeTextGenerator
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.ConversationPersister
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChatCompletionHandlerMemoryTest {

    private class RecordingPersister : ConversationPersister {
        var lastSessionId: Long? = null
        var lastMessages: List<ChatMessage>? = null
        override suspend fun persistConversation(sessionId: Long, messages: List<ChatMessage>) {
            lastSessionId = sessionId
            lastMessages = messages
        }
    }

    private fun augmenterReturning(text: String?): ContextAugmenter =
        object : ContextAugmenter {
            override suspend fun systemContextFor(projectId: Long, latestUserMessage: String) = text
        }

    @Test
    fun injectsAugmentedContextAndPersistsConversationWhenSessionActive() = runTest {
        val generator = FakeTextGenerator(tokens = listOf("answer"))
        val persister = RecordingPersister()
        val holder = ActiveSessionHolder().apply { set(projectId = 1, sessionId = 42) }
        val handler = ChatCompletionHandler(
            generator = generator,
            augmenter = augmenterReturning("Relevant long-term memory:\n- User likes tea"),
            persister = persister,
            activeSession = holder,
        )
        val request = ChatCompletionRequest(
            messages = listOf(ChatMessage("user", "what do I like?")),
            stream = true,
        )

        handler.streamSse(request).toList()

        assertThat(generator.lastPrompt).contains("User likes tea")
        assertThat(persister.lastSessionId).isEqualTo(42)
        assertThat(persister.lastMessages!!.map { it.role })
            .containsExactly("user", "assistant").inOrder()
        assertThat(persister.lastMessages!!.last().content).isEqualTo("answer")
    }

    @Test
    fun behavesLikePlainHandlerWhenNoSessionActive() = runTest {
        val generator = FakeTextGenerator(tokens = listOf("hi"))
        val persister = RecordingPersister()
        val handler = ChatCompletionHandler(
            generator = generator,
            augmenter = augmenterReturning("should not be used"),
            persister = persister,
            activeSession = ActiveSessionHolder(),
        )
        handler.streamSse(
            ChatCompletionRequest(messages = listOf(ChatMessage("user", "q")), stream = true),
        ).toList()

        assertThat(generator.lastPrompt).doesNotContain("should not be used")
        assertThat(persister.lastSessionId).isNull()
    }
}
