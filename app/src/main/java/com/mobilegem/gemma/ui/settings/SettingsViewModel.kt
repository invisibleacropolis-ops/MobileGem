package com.mobilegem.gemma.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mobilegem.gemma.inference.InferenceController
import com.mobilegem.gemma.logging.AppLog
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
    val loggingEnabled: Boolean = true,
    val logFilePath: String? = null,
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val modelFileManager: ModelFileManager,
    private val inferenceController: InferenceController,
    private val logFilePath: () -> String? = { null },
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val settings = settingsRepository.settings.first()
        val infState = inferenceController.state.first()
        val models = modelFileManager.listModels().map { it.name }
        _uiState.value = _uiState.value.copy(
            installedModels = models,
            activeModel = settings.activeModelFileName,
            backend = settings.backend,
            temperature = settings.temperature,
            modelLoaded = infState.loadedModelName != null,
            loggingEnabled = settings.loggingEnabled,
            logFilePath = logFilePath(),
        )
    }

    fun importModel(source: ContentSource): Job = viewModelScope.launch {
        AppLog.event("ui.settings", "importBegin", "name" to source.displayName)
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        runCatching { modelFileManager.import(source) }
            .onSuccess {
                AppLog.event(
                    "ui.settings", "importEnd",
                    "name" to source.displayName,
                    "path" to it.absolutePath,
                )
            }
            .onFailure {
                AppLog.error(
                    "ui.settings", "importEnd.failed", it,
                    "name" to source.displayName,
                )
                _uiState.value = _uiState.value.copy(error = it.message)
            }
        _uiState.value = _uiState.value.copy(busy = false)
        refresh()
    }

    fun selectModel(fileName: String): Job = viewModelScope.launch {
        AppLog.event("ui.settings", "selectModel", "fileName" to fileName)
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        settingsRepository.setActiveModel(fileName)
        loadActiveInternal()
        _uiState.value = _uiState.value.copy(busy = false)
        refresh()
    }

    fun setBackend(backend: InferenceBackend): Job = viewModelScope.launch {
        AppLog.event("ui.settings", "setBackend", "backend" to backend.name)
        settingsRepository.setBackend(backend)
        refresh()
    }

    fun setTemperature(value: Float): Job = viewModelScope.launch {
        AppLog.event("ui.settings", "setTemperature", "value" to value)
        settingsRepository.setTemperature(value)
        refresh()
    }

    fun setLoggingEnabled(value: Boolean): Job = viewModelScope.launch {
        settingsRepository.setLoggingEnabled(value)
        AppLog.event("ui.settings", "setLoggingEnabled", "value" to value)
        refresh()
    }

    /** Loads the persisted active model into inference; safe to call at startup. */
    fun loadActive(): Job = viewModelScope.launch { loadActiveInternal() }

    private suspend fun loadActiveInternal() {
        val settings = settingsRepository.settings.first()
        val name = settings.activeModelFileName ?: return
        val file = modelFileManager.resolve(name)
        if (!file.exists()) return
        AppLog.event(
            "ui.settings", "loadActive",
            "name" to name, "path" to file.absolutePath,
        )
        runCatching { inferenceController.loadModel(file.absolutePath, settings.backend) }
            .onFailure {
                AppLog.error(
                    "ui.settings", "loadActive.failed", it,
                    "name" to name,
                )
                _uiState.value = _uiState.value.copy(error = it.message)
            }
        refresh()
    }
}
