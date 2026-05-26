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
) {

    /**
     * Extracts durable facts from [transcript] using the on-device model,
     * embeds and stores each as a memory entry scoped to [projectId].
     * @return the memory entries that were stored.
     */
    suspend fun extractAndStore(
        projectId: Long, sessionId: Long, transcript: List<ChatMessage>,
    ): List<MemoryEntry> {
        AppLog.event(
            "selflearn", "selflearn.begin",
            "projectId" to projectId,
            "sessionId" to sessionId,
            "transcriptTurns" to transcript.size,
        )
        val prompt = buildExtractionPrompt(transcript)
        val output = generator.generate(prompt, temperature = 0.2f).toList().joinToString("")
        AppLog.event("selflearn", "selflearn.modelOutput", "outputChars" to output.length)
        val facts = FactListParser.parse(output)
        AppLog.event("selflearn", "selflearn.parsed", "factCount" to facts.size)

        val stored = facts.map { fact ->
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
        AppLog.event("selflearn", "selflearn.end", "storedCount" to stored.size)
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
