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
class SkillRepositoryTest {

    private lateinit var db: MemoryDatabase
    private lateinit var repo: SkillRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = SkillRepository(db.skillDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun createdSkillIsEnabledByDefaultAndAppearsForProject() = runTest {
        val id = repo.createSkill(
            projectId = 5, name = "Be terse", description = "", instructions = "Answer briefly.",
        )
        val enabled = repo.enabledForProject(5)
        assertThat(enabled.map { it.id }).containsExactly(id)
    }

    @Test
    fun disablingASkillExcludesItFromEnabledList() = runTest {
        repo.createSkill(5, "S", "", "do x")
        val skill = repo.enabledForProject(5).single()
        repo.setEnabled(skill, false)
        assertThat(repo.enabledForProject(5)).isEmpty()
    }
}
