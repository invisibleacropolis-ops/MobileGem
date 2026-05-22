package com.mobilegem.gemma.memory

import com.mobilegem.gemma.inference.Embedder
import com.mobilegem.gemma.memory.db.MemoryEntry

class MemoryRetriever(
    private val embedder: Embedder,
    private val ltm: LongTermMemoryRepository,
) {

    suspend fun retrieve(projectId: Long, query: String, topK: Int): List<MemoryEntry> {
        val candidates = ltm.entriesForProjectScope(projectId)
        if (candidates.isEmpty()) return emptyList()

        val queryVec = embedder.embed(query)
        return candidates
            .mapNotNull { entry ->
                if (entry.embedding.size != queryVec.size) null
                else entry to VectorMath.cosineSimilarity(queryVec, entry.embedding)
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }
}
