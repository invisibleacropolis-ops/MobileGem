package com.mobilegem.gemma.memory

import com.mobilegem.gemma.memory.db.CoreDao
import com.mobilegem.gemma.memory.db.Project
import com.mobilegem.gemma.memory.db.Session
import com.mobilegem.gemma.memory.db.StoredMessage
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val coreDao: CoreDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ConversationPersister {

    fun observeProjects(): Flow<List<Project>> = coreDao.observeProjects()
    fun observeSessions(projectId: Long): Flow<List<Session>> =
        coreDao.observeSessions(projectId)

    suspend fun projectById(id: Long): Project? = coreDao.projectById(id)
    suspend fun sessionById(id: Long): Session? = coreDao.sessionById(id)

    suspend fun createProject(name: String, description: String): Long {
        val now = clock()
        return coreDao.insertProject(
            Project(name = name, description = description, createdAt = now, updatedAt = now),
        )
    }

    suspend fun deleteProject(projectId: Long) = coreDao.deleteProject(projectId)

    suspend fun createSession(projectId: Long, title: String): Long {
        val now = clock()
        return coreDao.insertSession(
            Session(projectId = projectId, title = title, createdAt = now, updatedAt = now),
        )
    }

    suspend fun deleteSession(sessionId: Long) = coreDao.deleteSession(sessionId)

    suspend fun messagesForSession(sessionId: Long): List<StoredMessage> =
        coreDao.messagesForSession(sessionId)

    override suspend fun persistConversation(sessionId: Long, messages: List<ChatMessage>) {
        coreDao.deleteMessagesForSession(sessionId)
        val now = clock()
        messages.forEachIndexed { index, msg ->
            coreDao.insertMessage(
                StoredMessage(
                    sessionId = sessionId,
                    role = msg.role,
                    content = msg.content,
                    createdAt = now + index,
                ),
            )
        }
        coreDao.sessionById(sessionId)?.let {
            coreDao.updateSession(it.copy(updatedAt = now))
        }
    }
}
