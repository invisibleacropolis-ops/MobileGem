package com.mobilegem.gemma.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An [Embedder] that defers constructing its real delegate until the first
 * [embed] call. The delegate's construction (which loads a model file) thus
 * happens off the app-startup thread, inside a coroutine.
 */
class LazyEmbedder(factory: () -> Embedder) : Embedder {

    private val delegate: Embedder by lazy(factory)

    override val dimension: Int get() = delegate.dimension

    override suspend fun embed(text: String): FloatArray =
        withContext(Dispatchers.Default) { delegate.embed(text) }
}
