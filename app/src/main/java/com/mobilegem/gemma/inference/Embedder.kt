package com.mobilegem.gemma.inference

/** Produces a fixed-length embedding vector for a piece of text. */
interface Embedder {
    suspend fun embed(text: String): FloatArray

    /** Length of vectors returned by [embed]; used to size zero vectors on failure. */
    val dimension: Int
}
