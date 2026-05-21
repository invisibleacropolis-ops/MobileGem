package com.mobilegem.gemma.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilegem.gemma.inference.InferenceController
import com.mobilegem.gemma.model.ContentSource
import com.mobilegem.gemma.model.ModelFileManager
import com.mobilegem.gemma.settings.InferenceBackend
import com.mobilegem.gemma.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val installedModels: List<String> = emptyList(),
    val activeModel: String? = null,
    val backend: InferenceBackend = InferenceBackend.CPU,
    val temperature: Float = 0.8f,
    val modelLoaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val modelFileManager: ModelFileManager,
    private val inferenceController: InferenceController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val settings = settingsRepository.settings.first()
        val infState = inferenceController.state.first()
        _uiState.value = _uiState.value.copy(
            installedModels = modelFileManager.listModels().map { it.name },
            activeModel = settings.activeModelFileName,
            backend = settings.backend,
            temperature = settings.temperature,
            modelLoaded = infState.loadedModelName != null,
        )
    }

    fun importModel(source: ContentSource): Job = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        runCatching { modelFileManager.import(source) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        _uiState.value = _uiState.value.copy(busy = false)
        refresh()
    }

    fun selectModel(fileName: String): Job = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        settingsRepository.setActiveModel(fileName)
        loadActiveInternal()
        _uiState.value = _uiState.value.copy(busy = false)
        refresh()
    }

    fun setBackend(backend: InferenceBackend): Job = viewModelScope.launch {
        settingsRepository.setBackend(backend)
        refresh()
    }

    fun setTemperature(value: Float): Job = viewModelScope.launch {
        settingsRepository.setTemperature(value)
        refresh()
    }

    /** Loads the persisted active model into inference; safe to call at startup. */
    fun loadActive(): Job = viewModelScope.launch { loadActiveInternal() }

    private suspend fun loadActiveInternal() {
        val settings = settingsRepository.settings.first()
        val name = settings.activeModelFileName ?: return
        val file = modelFileManager.resolve(name)
        if (!file.exists()) return
        runCatching { inferenceController.loadModel(file.absolutePath, settings.backend) }
            .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        refresh()
    }
}
