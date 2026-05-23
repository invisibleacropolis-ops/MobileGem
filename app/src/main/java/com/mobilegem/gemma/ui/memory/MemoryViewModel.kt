package com.mobilegem.gemma.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.LongTermMemoryRepository
import com.mobilegem.gemma.memory.MemoryRepository
import com.mobilegem.gemma.memory.SelfLearningExtractor
import com.mobilegem.gemma.memory.SkillRepository
import com.mobilegem.gemma.memory.db.MemoryEntry
import com.mobilegem.gemma.memory.db.Project
import com.mobilegem.gemma.memory.db.Session
import com.mobilegem.gemma.memory.db.Skill
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MemoryUiState(
    val projects: List<Project> = emptyList(),
    val selectedProjectId: Long? = null,
    val sessions: List<Session> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val memories: List<MemoryEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class MemoryViewModel(
    private val memoryRepository: MemoryRepository,
    private val skillRepository: SkillRepository,
    private val longTermMemoryRepository: LongTermMemoryRepository,
    private val activeSessionHolder: ActiveSessionHolder,
    /** Supplied once a model is loaded; null disables self-learning. */
    private val extractorProvider: () -> SelfLearningExtractor? = { null },
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refreshProjects() }
    }

    private suspend fun refreshProjects() {
        _uiState.value = _uiState.value.copy(
            projects = memoryRepository.observeProjects().first(),
        )
    }

    private suspend fun refreshSelectedProject() {
        val projectId = _uiState.value.selectedProjectId ?: return
        _uiState.value = _uiState.value.copy(
            sessions = memoryRepository.observeSessions(projectId).first(),
            skills = skillRepository.observeForProjectScope(projectId).first(),
            memories = longTermMemoryRepository.observeForProjectScope(projectId).first(),
        )
    }

    fun createProject(name: String): Job = viewModelScope.launch {
        memoryRepository.createProject(name, "")
        refreshProjects()
    }

    fun selectProject(projectId: Long): Job = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(selectedProjectId = projectId)
        refreshSelectedProject()
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedProjectId = null, sessions = emptyList(),
            skills = emptyList(), memories = emptyList(),
        )
    }

    fun createSession(title: String): Job = viewModelScope.launch {
        val projectId = _uiState.value.selectedProjectId ?: return@launch
        memoryRepository.createSession(projectId, title)
        refreshSelectedProject()
    }

    /** Marks a session active so the next chat is bound to it. */
    fun openSession(sessionId: Long): Job = viewModelScope.launch {
        val projectId = _uiState.value.selectedProjectId ?: return@launch
        activeSessionHolder.set(projectId, sessionId)
    }

    fun addSkill(name: String, instructions: String): Job = viewModelScope.launch {
        val projectId = _uiState.value.selectedProjectId ?: return@launch
        skillRepository.createSkill(projectId, name, "", instructions)
        refreshSelectedProject()
    }

    fun toggleSkill(skill: Skill): Job = viewModelScope.launch {
        skillRepository.setEnabled(skill, !skill.enabled)
        refreshSelectedProject()
    }

    fun deleteSkill(skillId: Long): Job = viewModelScope.launch {
        skillRepository.deleteSkill(skillId)
        refreshSelectedProject()
    }

    fun deleteMemory(entryId: Long): Job = viewModelScope.launch {
        longTermMemoryRepository.delete(entryId)
        refreshSelectedProject()
    }

    /** Runs self-learning extraction over a session's stored transcript. */
    fun runSelfLearning(sessionId: Long): Job = viewModelScope.launch {
        val projectId = _uiState.value.selectedProjectId ?: return@launch
        val extractor = extractorProvider()
        if (extractor == null) {
            _uiState.value = _uiState.value.copy(message = "Load a model first")
            return@launch
        }
        _uiState.value = _uiState.value.copy(busy = true, message = null)
        val transcript = memoryRepository.messagesForSession(sessionId)
            .map { ChatMessage(it.role, it.content) }
        val stored = extractor.extractAndStore(projectId, sessionId, transcript)
        _uiState.value = _uiState.value.copy(
            busy = false, message = "Learned ${stored.size} new memories",
        )
        refreshSelectedProject()
    }
}
