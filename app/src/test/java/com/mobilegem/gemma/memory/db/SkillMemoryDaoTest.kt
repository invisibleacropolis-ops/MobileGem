package com.mobilegem.gemma.memory.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SkillMemoryDaoTest {

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
    fun enabledSkillsForProjectIncludeGlobalAndProjectScoped() = runTest {
        db.skillDao().insert(
            Skill(projectId = null, name = "Global", instructions = "g", enabled = true),
        )
        db.skillDao().insert(
            Skill(projectId = 1, name = "Proj", instructions = "p", enabled = true),
        )
        db.skillDao().insert(
            Skill(projectId = 1, name = "Off", instructions = "x", enabled = false),
        )
        db.skillDao().insert(
            Skill(projectId = 2, name = "Other", instructions = "o", enabled = true),
        )

        val enabled = db.skillDao().enabledForProject(1)
        assertThat(enabled.map { it.name }).containsExactly("Global", "Proj")
    }

    @Test
    fun memoryEntriesForProjectScopeIncludeGlobal() = runTest {
        db.memoryDao().insert(
            MemoryEntry(projectId = null, content = "global fact",
                embeddingBytes = byteArrayOf(127), embeddingScale = 1f / 127f,
                sourceSessionId = null, createdAt = 1),
        )
        db.memoryDao().insert(
            MemoryEntry(projectId = 1, content = "project fact",
                embeddingBytes = byteArrayOf(64), embeddingScale = 2f / 127f,
                sourceSessionId = null, createdAt = 2),
        )
        db.memoryDao().insert(
            MemoryEntry(projectId = 9, content = "unrelated",
                embeddingBytes = byteArrayOf(32), embeddingScale = 3f / 127f,
                sourceSessionId = null, createdAt = 3),
        )

        val entries = db.memoryDao().entriesForProjectScope(1)
        assertThat(entries.map { it.content })
            .containsExactly("global fact", "project fact")
    }
}
