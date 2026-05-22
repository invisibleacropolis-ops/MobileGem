package com.mobilegem.gemma.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(entry: MemoryEntry): Long

    @Query("DELETE FROM memory_entries WHERE id = :entryId")
    suspend fun delete(entryId: Long)

    @Query("SELECT * FROM memory_entries WHERE projectId = :projectId OR projectId IS NULL")
    suspend fun entriesForProjectScope(projectId: Long): List<MemoryEntry>

    @Query("SELECT * FROM memory_entries WHERE projectId = :projectId OR projectId IS NULL ORDER BY createdAt DESC")
    fun observeForProjectScope(projectId: Long): Flow<List<MemoryEntry>>
}
