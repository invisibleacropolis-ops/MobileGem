package com.mobilegem.gemma.memory

import com.mobilegem.gemma.memory.db.MemoryDao
import com.mobilegem.gemma.memory.db.MemoryEntry
import kotlinx.coroutines.flow.Flow

class LongTermMemoryRepository(
    private val memoryDao: MemoryDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun observeForProjectScope(projectId: Long): Flow<List<MemoryEntry>> =
        memoryDao.observeForProjectScope(projectId)

    suspend fun entriesForProjectScope(projectId: Long): List<MemoryEntry> =
        memoryDao.entriesForProjectScope(projectId)

    /**
     * Stores [embedding] as int8-quantized bytes. Callers pass and receive
     * `FloatArray` — the quantization is invisible above this layer.
     */
    suspend fun store(
        projectId: Long?,
        content: String,
        embedding: FloatArray,
        sourceSessionId: Long?,
    ): Long {
        val q = Quantization.quantize(embedding)
        return memoryDao.insert(
            MemoryEntry(
                projectId = projectId,
                content = content,
                embeddingBytes = q.bytes,
                embeddingScale = q.scale,
                sourceSessionId = sourceSessionId,
                createdAt = clock(),
            ),
        )
    }

    suspend fun delete(entryId: Long) = memoryDao.delete(entryId)
}

/** Dequantizes the entry's stored embedding back to a `FloatArray`. */
fun MemoryEntry.embeddingAsFloat(): FloatArray =
    Quantization.dequantize(embeddingBytes, embeddingScale)
