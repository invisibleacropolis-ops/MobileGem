package com.mobilegem.gemma.inference

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
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
        embedder.embed(text)
            .embeddingResult()
            .embeddings()
            .first()
            .floatEmbedding()
    }

    override fun close() = embedder.close()

    companion object {
        private const val MODEL_ASSET = "text_embedder.tflite"

        fun create(context: Context): MediaPipeTextEmbedder {
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build(),
                )
                .build()
            val embedder = TextEmbedder.createFromOptions(context, options)
            // Determine vector dimension from a probe embedding.
            val probe = embedder.embed("probe")
                .embeddingResult().embeddings().first().floatEmbedding()
            return MediaPipeTextEmbedder(embedder, probe.size)
        }
    }
}
