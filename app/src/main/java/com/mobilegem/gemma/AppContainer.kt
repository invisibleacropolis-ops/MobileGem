package com.mobilegem.gemma

import android.content.Context
import com.mobilegem.gemma.inference.Embedder
import com.mobilegem.gemma.inference.InferenceController
import com.mobilegem.gemma.inference.LazyEmbedder
import com.mobilegem.gemma.inference.LiteRtLmTextGenerator
import com.mobilegem.gemma.inference.MediaPipeTextEmbedder
import com.mobilegem.gemma.logging.FileLogger
import com.mobilegem.gemma.memory.ActiveSessionHolder
import com.mobilegem.gemma.memory.LongTermMemoryRepository
import com.mobilegem.gemma.memory.MemoryRepository
import com.mobilegem.gemma.memory.MemoryRetriever
import com.mobilegem.gemma.memory.SelfLearningExtractor
import com.mobilegem.gemma.memory.SkillRepository
import com.mobilegem.gemma.memory.db.MemoryDatabase
import com.mobilegem.gemma.model.ModelFileManager
import com.mobilegem.gemma.server.MemoryContextAugmenter
import com.mobilegem.gemma.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/** Manual service locator; built once in [GemmaApp.onCreate]. */
class AppContainer(context: Context) {

    val settingsRepository = SettingsRepository(context)
    val modelFileManager = ModelFileManager(File(context.filesDir, "models"))

    /** Long-lived scope for background infrastructure (logger writer, etc.). */
    val backgroundScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Logger — file output is gated by the persisted loggingEnabled setting. */
    val fileLogger: FileLogger = FileLogger(
        logsDir = File(context.filesDir, "logs"),
        enabledProvider = {
            runCatching { runBlocking { settingsRepository.settings.first().loggingEnabled } }
                .getOrDefault(true)
        },
        scope = backgroundScope,
    )

    private val database = MemoryDatabase.create(context)
    val memoryRepository = MemoryRepository(database.coreDao())
    val skillRepository = SkillRepository(database.skillDao())
    val longTermMemoryRepository = LongTermMemoryRepository(database.memoryDao())
    val activeSessionHolder = ActiveSessionHolder()

    /**
     * On-device text embedder. Wrapped in [LazyEmbedder] so the MediaPipe model
     * file is loaded on first use (inside a coroutine), not at app startup.
     */
    val embedder: Embedder = LazyEmbedder { MediaPipeTextEmbedder.create(context) }

    private val retriever = MemoryRetriever(embedder, longTermMemoryRepository)

    private val engineCacheDir = File(context.cacheDir, "litertlm")

    val inferenceController = InferenceController(
        activeSession = activeSessionHolder,
        augmenter = MemoryContextAugmenter(skillRepository, retriever),
        persister = memoryRepository,
        generatorFactory = { modelPath, backend ->
            LiteRtLmTextGenerator.create(modelPath, backend, engineCacheDir)
        },
    )

    /**
     * Builds a self-learning extractor over the currently-loaded generation
     * model. Returns null when no model is loaded.
     */
    fun selfLearningExtractor(): SelfLearningExtractor? {
        val generator = inferenceController.currentGenerator() ?: return null
        return SelfLearningExtractor(generator, embedder, longTermMemoryRepository)
    }
}
