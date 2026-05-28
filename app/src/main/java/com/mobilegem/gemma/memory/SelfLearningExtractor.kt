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
        AppLog.event(
            category = "selflearn",
            message = "begin",
            "projectId" to projectId,
            "sessionId" to sessionId,
            "transcriptTurns" to transcript.size,
            "maxAttempts" to temperatures.size,
        )

        val prompt = buildExtractionPrompt(transcript)
        var lastOutput = ""
        var facts: List<String> = emptyList()
        var attemptsUsed = 0

        for ((index, temperature) in temperatures.withIndex()) {
            attemptsUsed = index + 1
            AppLog.event(
                category = "selflearn",
                message = "attempt",
                "projectId" to projectId,
                "sessionId" to sessionId,
                "attempt" to attemptsUsed,
                "temperature" to temperature,
            )
            val output = generator.generate(prompt, temperature).toList().joinToString("")
            lastOutput = output
            AppLog.event(
                category = "selflearn",
                message = "modelOutput",
                "projectId" to projectId,
                "sessionId" to sessionId,
                "attempt" to attemptsUsed,
                "outputChars" to output.length,
            )
            facts = FactListParser.parse(output)
            AppLog.event(
                category = "selflearn",
                message = "parsed",
                "projectId" to projectId,
                "sessionId" to sessionId,
                "attempt" to attemptsUsed,
                "factCount" to facts.size,
            )
            if (facts.isNotEmpty()) break
        }

        if (facts.isEmpty() && lastOutput.isNotBlank()) {
            AppLog.warn(
                category = "selflearn",
                message = "parseEmpty",
                "projectId" to projectId,
                "sessionId" to sessionId,
                "attempts" to attemptsUsed,
                "rawOutput" to lastOutput.take(2000),
            )
        }

        val stored = facts.map { fact ->
            val embedding = embedder.embed(fact)
            val id = ltm.store(
                projectId = projectId,
                content = fact,
                embedding = embedding,
                sourceSessionId = sessionId,
            )
            val q = Quantization.quantize(embedding)
            MemoryEntry(
                id = id, projectId = projectId, content = fact,
                embeddingBytes = q.bytes, embeddingScale = q.scale,
                sourceSessionId = sessionId, createdAt = 0,
            )
        }

        AppLog.event(
            category = "selflearn",
            message = "end",
            "projectId" to projectId,
            "sessionId" to sessionId,
            "attemptsUsed" to attemptsUsed,
            "storedCount" to stored.size,
        )
        return stored
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
