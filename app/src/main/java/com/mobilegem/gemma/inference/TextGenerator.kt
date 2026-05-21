package com.mobilegem.gemma.inference

import kotlinx.coroutines.flow.Flow

/**
 * Generates assistant text for a fully-rendered prompt, streaming token chunks.
 * The prompt is already templated/concatenated by the caller.
 */
interface TextGenerator {
    fun generate(prompt: String, temperature: Float): Flow<String>
}
