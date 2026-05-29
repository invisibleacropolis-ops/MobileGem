package com.mobilegem.gemma.inference

import com.mobilegem.gemma.logging.AppLog
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.ConversationPersister
import com.mobilegem.gemma.server.ChatCompletionHandler
import com.mobilegem.gemma.server.ContextAugmenter
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
    private val activeSession: ActiveSessionHolder? = null,
    private val augmenter: ContextAugmenter? = null,
    private val persister: ConversationPersister? = null,
) {
    private val _state = MutableStateFlow(InferenceState())
    val state: StateFlow<InferenceState> = _state.asStateFlow()

    private var current: TextGenerator? = null

    /** The currently-loaded generator, or null if no model is loaded. */
    fun currentGenerator(): TextGenerator? = current

    @Synchronized
    fun loadModel(modelPath: String, backend: InferenceBackend) {
        AppLog.event(
            "inference", "loadModel.begin",
            "modelPath" to modelPath, "backend" to backend.name,
        )
        try {
            unload()
            val name = File(modelPath).name
            val generator = generatorFactory(modelPath, backend)
            current = generator
            val handler = ChatCompletionHandler(
                generator = generator,
                augmenter = augmenter,
                persister = persister,
                activeSession = activeSession,
            )
            server.start(handler, modelId = name)
            _state.value = InferenceState(loadedModelName = name, serverRunning = true)
            AppLog.event(
                "inference", "loadModel.end",
                "name" to name, "serverRunning" to true,
            )
        } catch (t: Throwable) {
            AppLog.error(
                "inference", "loadModel.failed", t,
                "modelPath" to modelPath, "backend" to backend.name,
            )
            throw t
        }
    }

    @Synchronized
    fun unload() {
        val prior = _state.value.loadedModelName
        AppLog.event("inference", "unload", "loadedModelName" to prior)
        server.stop()
        (current as? Closeable)?.close()
        current = null
        _state.value = InferenceState()
    }
}
