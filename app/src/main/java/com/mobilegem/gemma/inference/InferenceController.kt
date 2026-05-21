package com.mobilegem.gemma.inference

import com.mobilegem.gemma.server.ChatCompletionHandler
import com.mobilegem.gemma.server.LocalLlmServer
import com.mobilegem.gemma.settings.InferenceBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File

data class InferenceState(
    val loadedModelName: String? = null,
    val serverRunning: Boolean = false,
)

class InferenceController(
    val server: LocalLlmServer = LocalLlmServer(),
    private val generatorFactory: (modelPath: String, backend: InferenceBackend) -> TextGenerator =
        { path, backend -> LiteRtLmTextGenerator.create(path, backend) },
) {
    private val _state = MutableStateFlow(InferenceState())
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    private var current: TextGenerator? = null

    @Synchronized
    fun loadModel(modelPath: String, backend: InferenceBackend) {
        unload()
        val name = File(modelPath).name
        val generator = generatorFactory(modelPath, backend)
        current = generator
        server.start(ChatCompletionHandler(generator), modelId = name)
        _state.value = InferenceState(loadedModelName = name, serverRunning = true)
    }

    @Synchronized
    fun unload() {
        server.stop()
        (current as? Closeable)?.close()
        current = null
        _state.value = InferenceState()
    }
}
