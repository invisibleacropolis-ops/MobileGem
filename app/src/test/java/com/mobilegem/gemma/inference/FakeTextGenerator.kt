package com.mobilegem.gemma.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

class FakeTextGenerator(private val tokens: List<String>) : TextGenerator {
    var lastPrompt: String? = null
        private set

    override fun generate(prompt: String, temperature: Float): Flow<String> {
        lastPrompt = prompt
        return tokens.asFlow()
    }
}
