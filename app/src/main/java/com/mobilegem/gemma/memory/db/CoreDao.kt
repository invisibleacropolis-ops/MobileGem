package com.mobilegem.gemma.memory.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CoreDao {

    @Insert
    suspend fun insertProject(project: Project): Long

    @Update
    suspend fun updateProject(project: Project)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: Long)

    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun projectById(projectId: Long): Project?

    @Insert
    suspend fun insertSession(session: Session): Long

    @Update
    suspend fun updateSession(session: Session)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeSessions(projectId: Long): Flow<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun sessionById(sessionId: Long): Session?

    @Insert
    suspend fun insertMessage(message: StoredMessage): Long

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Long)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun messagesForSession(sessionId: Long): List<StoredMessage>
}
