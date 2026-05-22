package com.mobilegem.gemma.memory

import com.mobilegem.gemma.server.ChatMessage

/** Persists the full message list for a chat session, replacing any prior content. */
interface ConversationPersister {
    suspend fun persistConversation(sessionId: Long, messages: List<ChatMessage>)
}
