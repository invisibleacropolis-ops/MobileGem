package com.mobilegem.gemma.ui.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.LongTermMemoryRepository
import com.mobilegem.gemma.memory.MemoryRepository
import com.mobilegem.gemma.memory.SkillRepository
import com.mobilegem.gemma.memory.db.MemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryViewModelTest {

    private lateinit var db: MemoryDatabase
    private lateinit var holder: ActiveSessionHolder
    private lateinit var vm: MemoryViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        holder = ActiveSessionHolder()
        vm = MemoryViewModel(
            memoryRepository = MemoryRepository(db.coreDao()),
            skillRepository = SkillRepository(db.skillDao()),
            longTermMemoryRepository = LongTermMemoryRepository(db.memoryDao()),
            activeSessionHolder = holder,
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun createProjectThenSelectItShowsItsSessions() = runTest(testDispatcher) {
        vm.createProject("Research").join()
        val project = vm.uiState.first().projects.single()
        vm.selectProject(project.id).join()
        vm.createSession("Kickoff").join()

        val state = vm.uiState.first()
        assertThat(state.selectedProjectId).isEqualTo(project.id)
        assertThat(state.sessions.map { it.title }).containsExactly("Kickoff")
    }

    @Test
    fun openSessionSetsTheActiveSessionHolder() = runTest(testDispatcher) {
        vm.createProject("P").join()
        val project = vm.uiState.first().projects.single()
        vm.selectProject(project.id).join()
        vm.createSession("S").join()
        val session = vm.uiState.first().sessions.single()

        vm.openSession(session.id).join()

        assertThat(holder.current()?.projectId).isEqualTo(project.id)
        assertThat(holder.current()?.sessionId).isEqualTo(session.id)
    }

    @Test
    fun addSkillMakesItVisibleForTheProject() = runTest(testDispatcher) {
        vm.createProject("P").join()
        val project = vm.uiState.first().projects.single()
        vm.selectProject(project.id).join()
        vm.addSkill("Be terse", "Answer briefly.").join()

        assertThat(vm.uiState.first().skills.map { it.name }).containsExactly("Be terse")
    }
}
