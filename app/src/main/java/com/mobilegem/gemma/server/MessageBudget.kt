package com.mobilegem.gemma.server

/**
 * Trims the oldest non-system turns from a `messages` list until the estimated
 * total fits within [maxInputTokens]. Always keeps:
 *   - All `system` messages (they're typically small + carry critical context).
 *   - The LAST user message (the model can't answer without it).
 *
 * If those non-droppable messages alone exceed the budget, this returns them
 * anyway — the model may truncate internally, but the alternative (dropping
 * the last user turn) is meaningless.
 */
object MessageBudget {

    fun fitWithinBudget(messages: List<ChatMessage>, maxInputTokens: Int): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        if (TokenEstimator.estimateMessages(messages) <= maxInputTokens) return messages

        val systemMessages = messages.filter { it.role == "system" }
        val nonSystem = messages.filter { it.role != "system" }
        if (nonSystem.isEmpty()) return messages

        val lastUserIndex = nonSystem.indexOfLast { it.role == "user" }
            .takeIf { it >= 0 } ?: (nonSystem.size - 1)
        val mustKeep = systemMessages + nonSystem[lastUserIndex]
        val droppable = nonSystem.toMutableList().also { it.removeAt(lastUserIndex) }

        // Walk from the NEWEST droppable backward, keeping while there's budget.
        val budget = maxInputTokens - TokenEstimator.estimateMessages(mustKeep)
        val kept = ArrayDeque<ChatMessage>()
        var remaining = budget
        for (msg in droppable.asReversed()) {
            val cost = TokenEstimator.estimateMessage(msg)
            if (cost > remaining) break
            kept.addFirst(msg)
            remaining -= cost
        }

        // Reassemble: system messages first, then the kept middle, then last user.
        return systemMessages + kept + listOf(nonSystem[lastUserIndex])
    }
}
