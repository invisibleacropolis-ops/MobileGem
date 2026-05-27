package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageBudgetTest {

    @Test
    fun keepsAllWhenWithinBudget() {
        val messages = listOf(
            ChatMessage("user", "hi"),
            ChatMessage("assistant", "hello"),
            ChatMessage("user", "how are you?"),
        )
        val result = MessageBudget.fitWithinBudget(messages, maxInputTokens = 1000)
        assertThat(result).isEqualTo(messages)
    }

    @Test
    fun dropsOldestNonSystemMessagesUntilFits() {
        val long = "x".repeat(400) // ~100 tokens per content
        val messages = listOf(
            ChatMessage("system", "be terse"),
            ChatMessage("user", long),
            ChatMessage("assistant", long),
            ChatMessage("user", long),
            ChatMessage("assistant", long),
            ChatMessage("user", "latest"),
        )
        val result = MessageBudget.fitWithinBudget(messages, maxInputTokens = 250)
        // System always kept; the latest user turn always kept.
        assertThat(result.first().role).isEqualTo("system")
        assertThat(result.last().content).isEqualTo("latest")
        // Older turns were dropped.
        assertThat(result.size).isLessThan(messages.size)
    }

    @Test
    fun alwaysKeepsTheLastUserMessageEvenWhenBudgetIsTiny() {
        val messages = listOf(
            ChatMessage("user", "old"),
            ChatMessage("assistant", "ok"),
            ChatMessage("user", "x".repeat(2000)),
        )
        val result = MessageBudget.fitWithinBudget(messages, maxInputTokens = 1)
        assertThat(result.last().content).isEqualTo(messages.last().content)
    }
}
