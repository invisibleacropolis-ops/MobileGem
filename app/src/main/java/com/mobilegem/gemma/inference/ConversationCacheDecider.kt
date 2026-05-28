package com.mobilegem.gemma.inference

import com.mobilegem.gemma.server.ChatMessage

/**
 * Pure decision logic for whether a cached LiteRT-LM `Conversation` can be
 * reused for a new request, or whether the cache must be rebuilt.
 *
 * Reuse is allowed ONLY when the incoming message list is the previously-sent
 * list with EXACTLY one trailing `user` message appended, AND the prior
 * conversation ended on an `assistant` turn (i.e. the previous exchange is
 * closed). This conservative rule guarantees KV-cache correctness: if the
 * WebView edits, regenerates, or deletes any prior turn, or if the prior
 * history is not in a closed alternating state, the cache is invalidated.
 */
object ConversationCacheDecider {

    sealed interface Decision {
        /** Send only [newUserMessage] to the existing cached Conversation. */
        data class Incremental(val newUserMessage: ChatMessage) : Decision

        /** Close and recreate the Conversation; replay [fullMessages]. */
        data class Rebuild(val fullMessages: List<ChatMessage>) : Decision
    }

    fun decide(previouslySent: List<ChatMessage>?, incoming: List<ChatMessage>): Decision {
        if (previouslySent == null || previouslySent.isEmpty()) {
            return Decision.Rebuild(incoming)
        }
        if (incoming.size != previouslySent.size + 1) {
            return Decision.Rebuild(incoming)
        }
        for (i in previouslySent.indices) {
            if (previouslySent[i].role != incoming[i].role ||
                previouslySent[i].content != incoming[i].content
            ) {
                return Decision.Rebuild(incoming)
            }
        }
        if (previouslySent.last().role != "assistant") return Decision.Rebuild(incoming)
        val appended = incoming.last()
        if (appended.role != "user") return Decision.Rebuild(incoming)
        return Decision.Incremental(appended)
    }
}
