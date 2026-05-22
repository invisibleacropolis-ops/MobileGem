package com.mobilegem.gemma.server

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeEmbedder
import com.mobilegem.gemma.memory.LongTermMemoryRepository
import com.mobilegem.gemma.memory.MemoryRetriever
import com.mobilegem.gemma.memory.SkillRepository
import com.mobilegem.gemma.memory.db.MemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryContextAugmenterTest {

    private lateinit var db: MemoryDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun augmenter(embedder: FakeEmbedder): MemoryContextAugmenter {
        val ltm = LongTermMemoryRepository(db.memoryDao()) { 1L }
        return MemoryContextAugmenter(
            skillRepository = SkillRepository(db.skillDao()),
            retriever = MemoryRetriever(embedder, ltm),
            topK = 3,
        )
    }

    @Test
    fun includesEnabledSkillsAndRetrievedMemory() = runTest {
        db.skillDao().insert(
            com.mobilegem.gemma.memory.db.Skill(
                projectId = 1, name = "Terse", instructions = "Answer briefly.", enabled = true,
            ),
        )
        db.memoryDao().insert(
            com.mobilegem.gemma.memory.db.MemoryEntry(
                projectId = 1, content = "User is a Kotlin developer.",
                embedding = floatArrayOf(1f, 0f), sourceSessionId = null, createdAt = 1,
            ),
        )
        val embedder = FakeEmbedder(mapOf("tell me about kotlin" to floatArrayOf(1f, 0f)))

        val context = augmenter(embedder).systemContextFor(1, "tell me about kotlin")

        assertThat(context).isNotNull()
        assertThat(context!!).contains("Answer briefly.")
        assertThat(context).contains("User is a Kotlin developer.")
    }

    @Test
    fun returnsNullWhenNoSkillsOrMemoryExist() = runTest {
        val embedder = FakeEmbedder(mapOf("q" to floatArrayOf(1f, 0f)))
        assertThat(augmenter(embedder).systemContextFor(1, "q")).isNull()
    }
}
