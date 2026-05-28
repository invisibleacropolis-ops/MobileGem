package com.mobilegem.gemma.memory

import com.mobilegem.gemma.inference.Embedder
import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.memory.db.MemoryEntry

class MemoryRetriever(
    private val embedder: Embedder,
    private val ltm: LongTermMemoryRepository,
) {

    suspend fun retrieve(projectId: Long, query: String, topK: Int): List<MemoryEntry> {
        val candidates = ltm.entriesForProjectScope(projectId)
        if (candidates.isEmpty()) {
            AppLog.event(
                "retriever", "retriever.retrieve",
                "projectId" to projectId, "candidates" to 0,
                "returned" to 0, "topSimilarity" to null,
            )
            return emptyList()
        }

        val queryVec = embedder.embed(query)
        val scored = candidates
            .mapNotNull { entry ->
                val entryVec = entry.embeddingAsFloat()
                if (entryVec.size != queryVec.size) null
                else entry to VectorMath.cosineSimilarity(queryVec, entryVec)
            }
            .sortedByDescending { it.second }
            .take(topK)

        AppLog.event(
            "retriever", "retriever.retrieve",
            "projectId" to projectId,
            "candidates" to candidates.size,
            "returned" to scored.size,
            "topSimilarity" to scored.firstOrNull()?.second,
        )
        return scored.map { it.first }
    }
}
