package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeSessionedTextGeneratorTest {

    @Test
    fun emitsScriptedTokensAndRecordsCallShape() = runTest {
        val gen = FakeSessionedTextGenerator(perCallOutputs = listOf("alpha", "beta"))
        val out1 = gen.generateSession(
            sessionId = "S1",
            systemContext = "sys",
            messages = listOf(ChatMessage("user", "hi")),
            temperature = 0.5f,
        ).toList().joinToString("")
        val out2 = gen.generateSession(
            sessionId = "S1",
            systemContext = null,
            messages = listOf(
                ChatMessage("user", "hi"),
                ChatMessage("assistant", "alpha"),
                ChatMessage("user", "again"),
            ),
            temperature = 0.5f,
        ).toList().joinToString("")

        assertThat(out1).isEqualTo("alpha")
        assertThat(out2).isEqualTo("beta")
        assertThat(gen.calls).hasSize(2)
        assertThat(gen.calls[0].sessionId).isEqualTo("S1")
        assertThat(gen.calls[1].messages.last().content).isEqualTo("again")
    }
}
