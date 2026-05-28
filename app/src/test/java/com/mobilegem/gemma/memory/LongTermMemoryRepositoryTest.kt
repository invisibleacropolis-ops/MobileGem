package com.mobilegem.gemma.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.memory.db.MemoryDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LongTermMemoryRepositoryTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repo: LongTermMemoryRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = LongTermMemoryRepository(db.memoryDao()) { 500L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun storedEntryIsReturnedForItsProjectScope() = runTest {
        repo.store(
            projectId = 3, content = "User prefers metric units",
            embedding = floatArrayOf(0.1f, 0.2f), sourceSessionId = 7,
        )
        val entries = repo.entriesForProjectScope(3)
        assertThat(entries).hasSize(1)
        assertThat(entries.single().content).isEqualTo("User prefers metric units")
        val roundtrip = com.mobilegem.gemma.memory.Quantization.dequantize(
            entries.single().embeddingBytes, entries.single().embeddingScale,
        )
        assertThat(roundtrip[0]).isWithin(0.01f).of(0.1f)
        assertThat(roundtrip[1]).isWithin(0.01f).of(0.2f)
    }
}
