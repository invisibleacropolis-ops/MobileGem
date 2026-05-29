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
class SessionRouterTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repo: MemoryRepository
    private lateinit var active: ActiveSessionHolder
    private lateinit var router: SessionRouter

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = MemoryRepository(db.coreDao()) { 1000L }
        active = ActiveSessionHolder()
        router = SessionRouter(repo, active)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun firstMessageCreatesSessionTitledByFirstLine() = runTest {
        router.route(listOf(ChatMessage("user", "How do I parse JSON?\nMore detail.")))

        val current = active.current()
        assertThat(current).isNotNull()
        val session = repo.sessionById(current!!.sessionId)
        assertThat(session?.title).isEqualTo("How do I parse JSON?")
    }

    @Test
    fun continuationReusesTheSameSession() = runTest {
        router.route(listOf(ChatMessage("user", "Hello")))
        val first = active.current()!!.sessionId

        router.route(
            listOf(
                ChatMessage("user", "Hello"),
                ChatMessage("assistant", "Hi!"),
                ChatMessage("user", "Tell me more"),
            ),
        )

        assertThat(active.current()!!.sessionId).isEqualTo(first)
        assertThat(repo.observeSessions(active.current()!!.projectId).first()).hasSize(1)
    }

    @Test
    fun newConversationCreatesNewSession() = runTest {
        router.route(listOf(ChatMessage("user", "First conversation")))
        val first = active.current()!!.sessionId

        router.route(listOf(ChatMessage("user", "A different topic")))
        val second = active.current()!!.sessionId

        assertThat(second).isNotEqualTo(first)
        assertThat(repo.observeSessions(active.current()!!.projectId).first()).hasSize(2)
    }

    @Test
    fun allConversationsShareTheSingleDefaultProject() = runTest {
        router.route(listOf(ChatMessage("user", "One")))
        router.route(listOf(ChatMessage("user", "Two")))

        val projects = repo.observeProjects().first()
        assertThat(projects).hasSize(1)
        assertThat(projects.single().name).isEqualTo(SessionRouter.DEFAULT_PROJECT_NAME)
    }

    @Test
    fun blankOrSystemOnlyMessagesAreIgnored() = runTest {
        router.route(listOf(ChatMessage("system", "You are helpful.")))
        router.route(listOf(ChatMessage("user", "   ")))

        assertThat(active.current()).isNull()
        assertThat(repo.observeProjects().first()).isEmpty()
    }

    @Test
    fun longFirstLineIsTruncated() = runTest {
        val longLine = "x".repeat(200)
        router.route(listOf(ChatMessage("user", longLine)))

        val title = repo.sessionById(active.current()!!.sessionId)?.title.orEmpty()
        assertThat(title.length).isAtMost(80)
        assertThat(title).endsWith("…")
    }
}
