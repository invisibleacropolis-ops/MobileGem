package com.mobilegem.gemma.inference

import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.Flow

/**
 * Optional session-aware extension of [TextGenerator]. Implementations MAY
 * use [sessionId] to maintain a per-session KV-cache (e.g. a long-lived
 * LiteRT-LM [com.google.ai.edge.litertlm.Conversation]). Callers pass the
 * full message list as the source of truth; the implementation detects
 * prefix-extensions and only processes the new user message when possible.
 *
 * Implementations MUST behave correctly even if [sessionId] is null
 * (e.g. anonymous chat from outside the active session) — in that case,
 * they should fall back to the stateless [TextGenerator.generate] behaviour.
 */
interface SessionedTextGenerator : TextGenerator {
    fun generateSession(
        sessionId: String?,
        systemContext: String?,
        messages: List<ChatMessage>,
        temperature: Float,
    ): Flow<String>
}
