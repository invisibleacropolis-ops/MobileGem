package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GemmaPromptBuilderTest {

    @Test
    fun rendersUserAndAssistantTurnsWithGemmaTemplate() {
        val prompt = GemmaPromptBuilder.build(
            listOf(
                ChatMessage("user", "Hello"),
                ChatMessage("assistant", "Hi there"),
                ChatMessage("user", "How are you?"),
            )
        )
        assertThat(prompt).isEqualTo(
            "<start_of_turn>user\nHello<end_of_turn>\n" +
                "<start_of_turn>model\nHi there<end_of_turn>\n" +
                "<start_of_turn>user\nHow are you?<end_of_turn>\n" +
                "<start_of_turn>model\n"
        )
    }

    @Test
    fun foldsSystemMessageIntoFirstUserTurn() {
        val prompt = GemmaPromptBuilder.build(
            listOf(
                ChatMessage("system", "Be terse."),
                ChatMessage("user", "Hi"),
            )
        )
        assertThat(prompt).isEqualTo(
            "<start_of_turn>user\nBe terse.\n\nHi<end_of_turn>\n" +
                "<start_of_turn>model\n"
        )
    }
}
