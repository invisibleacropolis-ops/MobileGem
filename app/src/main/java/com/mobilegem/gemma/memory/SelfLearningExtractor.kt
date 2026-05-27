package com.mobilegem.gemma.memory

import com.mobilegem.gemma.inference.Embedder
import com.mobilegem.gemma.inference.TextGenerator
import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.memory.db.MemoryEntry
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.toList

class SelfLearningExtractor(
    private val generator: TextGenerator,
    private val embedder: Embedder,
    private val ltm: LongTermMemoryRepository,
    /** Temperatures to try in order; length determines the maximum attempt count. */
    private val temperatures: List<Float> = listOf(0.2f, 0.5f, 0.8f),
) {

    /**
     * Extracts durable facts from [transcript] via the on-device model. Retries
     * with progressively higher temperature when the parser yields zero facts
     * AND the model actually produced output (so retries only happen when the
     * model "tried" but mis-formatted). Returns the [MemoryEntry]s stored.
     */
    suspend fun extractAndStore(
        projectId: Long, sessionId: Long, transcript: List<ChatMessage>,
    ): List<MemoryEntry> {
        val prompt = buildExtractionPrompt(transcript)

        var lastOutput = ""
        var facts: List<String> = emptyList()
        for (temperature in temperatures) {
            val output = generator.generate(prompt, temperature).toList().joinToString("")
            lastOutput = output
            facts = FactListParser.parse(output)
            if (facts.isNotEmpty()) break
        }

        if (facts.isEmpty() && lastOutput.isNotBlank()) {
            AppLog.warn(
                category = "selflearn",
                message = "parseEmpty",
                "projectId" to projectId,
                "sessionId" to sessionId,
                "attempts" to temperatures.size,
                "rawOutput" to lastOutput.take(2000),
            )
        }

        return facts.map { fact ->
            val embedding = embedder.embed(fact)
            val id = ltm.store(
                projectId = projectId,
                content = fact,
                embedding = embedding,
                sourceSessionId = sessionId,
            )
            MemoryEntry(
                id = id, projectId = projectId, content = fact,
                embedding = embedding, sourceSessionId = sessionId, createdAt = 0,
            )
        }
    }

    private fun buildExtractionPrompt(transcript: List<ChatMessage>): String {
        val convo = transcript.joinToString("\n") { "${it.role}: ${it.content}" }
        return "<start_of_turn>user\n" +
            "Read the following conversation and extract durable, factual things " +
            "worth remembering about the user or project for future conversations " +
            "(preferences, decisions, persistent context). Ignore one-off chatter. " +
            "Respond with ONLY a JSON array of short fact strings, or [] if there is " +
            "nothing durable.\n\n" +
            "Conversation:\n$convo<end_of_turn>\n" +
            "<start_of_turn>model\n"
    }
}
