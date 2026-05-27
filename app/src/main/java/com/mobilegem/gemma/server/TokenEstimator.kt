package com.mobilegem.gemma.server

/**
 * Cheap heuristic SentencePiece-token counter for Gemma-style models. Tuned
 * to overestimate slightly so context-window truncation does not under-fill.
 *
 * - English averages ~4 characters per token; we use `ceil(chars / 4)`.
 * - Per-message overhead accounts for `<start_of_turn>role\n…<end_of_turn>\n`
 *   template wrapping (4 tokens minimum).
 */
object TokenEstimator {

    private const val CHARS_PER_TOKEN = 4
    private const val PER_MESSAGE_OVERHEAD = 4

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }

    fun estimateMessage(message: ChatMessage): Int =
        estimate(message.content) + PER_MESSAGE_OVERHEAD

    fun estimateMessages(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateMessage(it) }
}
