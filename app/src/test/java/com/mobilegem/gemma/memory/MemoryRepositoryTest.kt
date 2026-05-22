package com.mobilegem.gemma.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.memory.db.MemoryDatabase
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryRepositoryTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repo: MemoryRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = MemoryRepository(db.coreDao()) { 1000L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createsProjectAndSession() = runTest {
        val projectId = repo.createProject("Research", "notes")
        val sessionId = repo.createSession(projectId, "First chat")

        assertThat(repo.observeProjects().first().single().name).isEqualTo("Research")
        assertThat(repo.observeSessions(projectId).first().single().title)
            .isEqualTo("First chat")
        assertThat(repo.sessionById(sessionId)?.projectId).isEqualTo(projectId)
    }

    @Test
    fun persistConversationReplacesSessionMessages() = runTest {
        val projectId = repo.createProject("P", "")
        val sessionId = repo.createSession(projectId, "S")

        repo.persistConversation(sessionId, listOf(ChatMessage("user", "hi")))
        repo.persistConversation(
            sessionId,
            listOf(
                ChatMessage("user", "hi"),
                ChatMessage("assistant", "hello"),
            ),
        )

        val messages = repo.messagesForSession(sessionId)
        assertThat(messages.map { it.role to it.content })
            .containsExactly("user" to "hi", "assistant" to "hello").inOrder()
    }
}
