package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.server.ChatMessage
import org.junit.Test

class ConversationCacheDeciderTest {

    @Test
    fun firstCallRequestsRebuildWithAllMessages() {
        val decision = ConversationCacheDecider.decide(
            previouslySent = null,
            incoming = listOf(ChatMessage("user", "hi")),
        )
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Rebuild::class.java)
        val rebuild = decision as ConversationCacheDecider.Decision.Rebuild
        assertThat(rebuild.fullMessages.map { it.content }).containsExactly("hi")
    }

    @Test
    fun strictPrefixExtensionRequestsIncremental() {
        val prior = listOf(
            ChatMessage("user", "hi"),
            ChatMessage("assistant", "hello"),
        )
        val incoming = prior + ChatMessage("user", "how are you?")
        val decision = ConversationCacheDecider.decide(prior, incoming)
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Incremental::class.java)
        val inc = decision as ConversationCacheDecider.Decision.Incremental
        assertThat(inc.newUserMessage.content).isEqualTo("how are you?")
    }

    @Test
    fun divergenceTriggersRebuild() {
        val prior = listOf(
            ChatMessage("user", "old"),
            ChatMessage("assistant", "old-resp"),
        )
        val incoming = listOf(
            ChatMessage("user", "different"),
            ChatMessage("assistant", "different-resp"),
            ChatMessage("user", "follow-up"),
        )
        val decision = ConversationCacheDecider.decide(prior, incoming)
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Rebuild::class.java)
    }

    @Test
    fun nonAppendExtensionTriggersRebuild() {
        // Same prefix but extra middle turn — not a strict prefix-with-one-more-user.
        val prior = listOf(ChatMessage("user", "a"))
        val incoming = listOf(
            ChatMessage("user", "a"),
            ChatMessage("user", "b"), // two user msgs without assistant in between
        )
        val decision = ConversationCacheDecider.decide(prior, incoming)
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Rebuild::class.java)
    }

    @Test
    fun systemMessageChangeTriggersRebuild() {
        val prior = listOf(
            ChatMessage("system", "be terse"),
            ChatMessage("user", "hi"),
        )
        val incoming = listOf(
            ChatMessage("system", "be VERBOSE"),
            ChatMessage("user", "hi"),
            ChatMessage("assistant", "hello"),
            ChatMessage("user", "next"),
        )
        val decision = ConversationCacheDecider.decide(prior, incoming)
        assertThat(decision).isInstanceOf(ConversationCacheDecider.Decision.Rebuild::class.java)
    }
}
