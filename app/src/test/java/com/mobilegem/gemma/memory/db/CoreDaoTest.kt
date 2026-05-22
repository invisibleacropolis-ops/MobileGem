package com.mobilegem.gemma.memory.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CoreDaoTest {

    private lateinit var db: MemoryDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertsAndListsProjects() = runTest {
        val id = db.coreDao().insertProject(
            Project(name = "Research", createdAt = 1, updatedAt = 1),
        )
        val projects = db.coreDao().observeProjects().first()
        assertThat(projects.map { it.name }).containsExactly("Research")
        assertThat(projects.single().id).isEqualTo(id)
    }

    @Test
    fun sessionsAreScopedToProjectAndCascadeOnDelete() = runTest {
        val projectId = db.coreDao().insertProject(
            Project(name = "P", createdAt = 1, updatedAt = 1),
        )
        db.coreDao().insertSession(
            Session(projectId = projectId, title = "S1", createdAt = 1, updatedAt = 1),
        )
        assertThat(db.coreDao().observeSessions(projectId).first()).hasSize(1)

        db.coreDao().deleteProject(projectId)
        assertThat(db.coreDao().observeSessions(projectId).first()).isEmpty()
    }

    @Test
    fun messagesCanBeReplacedForASession() = runTest {
        val projectId = db.coreDao().insertProject(
            Project(name = "P", createdAt = 1, updatedAt = 1),
        )
        val sessionId = db.coreDao().insertSession(
            Session(projectId = projectId, title = "S", createdAt = 1, updatedAt = 1),
        )
        db.coreDao().insertMessage(
            StoredMessage(sessionId = sessionId, role = "user", content = "old", createdAt = 1),
        )
        db.coreDao().deleteMessagesForSession(sessionId)
        db.coreDao().insertMessage(
            StoredMessage(sessionId = sessionId, role = "user", content = "new", createdAt = 2),
        )
        val messages = db.coreDao().messagesForSession(sessionId)
        assertThat(messages.map { it.content }).containsExactly("new")
    }
}
