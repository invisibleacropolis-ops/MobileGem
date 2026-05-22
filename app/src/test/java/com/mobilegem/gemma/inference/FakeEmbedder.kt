package com.mobilegem.gemma.inference

class FakeEmbedder(
    private val vectors: Map<String, FloatArray>,
) : Embedder {

    override val dimension: Int = vectors.values.firstOrNull()?.size ?: 2

    val embeddedTexts = mutableListOf<String>()

    override suspend fun embed(text: String): FloatArray {
        embeddedTexts += text
        return vectors[text] ?: FloatArray(dimension)
    }
}
