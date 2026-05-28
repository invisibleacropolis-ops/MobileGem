package com.mobilegem.gemma.inference

import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test fake for [SessionedTextGenerator]. Returns one entry from
 * [perCallOutputs] per call (clamped to the last entry on overflow), and
 * records the shape of each call for assertions.
 */
class FakeSessionedTextGenerator(
    private val perCallOutputs: List<String>,
) : SessionedTextGenerator {

    data class Call(
        val sessionId: String?,
        val systemContext: String?,
        val messages: List<ChatMessage>,
        val temperature: Float,
    )

    val calls: MutableList<Call> = mutableListOf()
    private var idx = 0

    override fun generate(prompt: String, temperature: Float): Flow<String> {
        // Single-shot fallback path; records as session=null.
        calls += Call(null, null, listOf(ChatMessage("user", prompt)), temperature)
        return flowOf(nextOutput())
    }

    override fun generateSession(
        sessionId: String?,
        systemContext: String?,
        messages: List<ChatMessage>,
        temperature: Float,
    ): Flow<String> {
        calls += Call(sessionId, systemContext, messages, temperature)
        return flowOf(nextOutput())
    }

    private fun nextOutput(): String {
        val i = if (idx < perCallOutputs.size) idx else perCallOutputs.lastIndex
        idx++
        return perCallOutputs[i]
    }
}
