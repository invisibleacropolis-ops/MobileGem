package com.mobilegem.gemma.inference

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.mobilegem.gemma.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Produces text embeddings on-device via MediaPipe TextEmbedder, backed by the
 * bundled `text_embedder.tflite` asset. All MediaPipe API usage is confined here.
 */
class MediaPipeTextEmbedder private constructor(
    private val embedder: TextEmbedder,
    override val dimension: Int,
) : Embedder, Closeable {

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        try {
            val out = embedder.embed(text)
                .embeddingResult()
                .embeddings()
                .first()
                .floatEmbedding()
            AppLog.event(
                "embedder", "embed",
                "textChars" to text.length, "dimension" to out.size,
            )
            out
        } catch (t: Throwable) {
            AppLog.error("embedder", "embed.failed", t, "textChars" to text.length)
            throw t
        }
    }

    override fun close() = embedder.close()

    companion object {
        private const val MODEL_ASSET = "text_embedder.tflite"

        fun create(context: Context): MediaPipeTextEmbedder {
            AppLog.event("embedder", "create.begin", "asset" to MODEL_ASSET)
            return try {
                val options = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build(),
                    )
                    .build()
                val embedder = TextEmbedder.createFromOptions(context, options)
                // Determine vector dimension from a probe embedding.
                val probe = embedder.embed("probe")
                    .embeddingResult().embeddings().first().floatEmbedding()
                AppLog.event("embedder", "create.end", "dimension" to probe.size)
                MediaPipeTextEmbedder(embedder, probe.size)
            } catch (t: Throwable) {
                AppLog.error("embedder", "create.failed", t)
                throw t
            }
        }
    }
}
