package com.mobilegem.gemma.memory

import com.mobilegem.gemma.logging.AppLog
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

    suspend fun store(
        projectId: Long?, content: String, embedding: FloatArray, sourceSessionId: Long?,
    ): Long {
        AppLog.event(
            "memory", "memory.ltm.store",
            "projectId" to projectId,
            "chars" to content.length,
            "dim" to embedding.size,
        )
        return memoryDao.insert(
            MemoryEntry(
                projectId = projectId,
                content = content,
                embedding = embedding,
                sourceSessionId = sourceSessionId,
                createdAt = clock(),
            ),
        )
    }

    suspend fun delete(entryId: Long) {
        AppLog.event("memory", "memory.ltm.delete", "entryId" to entryId)
        memoryDao.delete(entryId)
    }
}
