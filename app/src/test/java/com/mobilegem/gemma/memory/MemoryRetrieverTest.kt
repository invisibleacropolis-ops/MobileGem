package com.mobilegem.gemma.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeEmbedder
import com.mobilegem.gemma.memory.db.MemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryRetrieverTest {

    private lateinit var db: MemoryDatabase
    private lateinit var ltm: LongTermMemoryRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        ltm = LongTermMemoryRepository(db.memoryDao()) { 1L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun returnsEntriesRankedByCosineSimilarityToTheQuery() = runTest {
        ltm.store(1, "about cats", floatArrayOf(1f, 0f), null)
        ltm.store(1, "about dogs", floatArrayOf(0f, 1f), null)

        val embedder = FakeEmbedder(mapOf("feline question" to floatArrayOf(0.9f, 0.1f)))
        val retriever = MemoryRetriever(embedder, ltm)

        val results = retriever.retrieve(projectId = 1, query = "feline question", topK = 2)
        assertThat(results.map { it.content })
            .containsExactly("about cats", "about dogs").inOrder()
    }

    @Test
    fun topKLimitsTheNumberOfResults() = runTest {
        ltm.store(1, "a", floatArrayOf(1f, 0f), null)
        ltm.store(1, "b", floatArrayOf(0.5f, 0.5f), null)
        ltm.store(1, "c", floatArrayOf(0f, 1f), null)

        val embedder = FakeEmbedder(mapOf("q" to floatArrayOf(1f, 0f)))
        val retriever = MemoryRetriever(embedder, ltm)

        assertThat(retriever.retrieve(1, "q", topK = 1)).hasSize(1)
    }

    @Test
    fun returnsEmptyWhenNoEntriesExist() = runTest {
        val embedder = FakeEmbedder(mapOf("q" to floatArrayOf(1f)))
        val retriever = MemoryRetriever(embedder, ltm)
        assertThat(retriever.retrieve(99, "q", topK = 5)).isEmpty()
    }
}
