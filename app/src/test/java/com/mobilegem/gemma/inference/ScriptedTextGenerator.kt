package com.mobilegem.gemma.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test [TextGenerator] that returns a different output on each successive
 * [generate] invocation. After the script runs out, repeats the last script
 * entry. Useful for verifying retry logic.
 */
class ScriptedTextGenerator(private val scripts: List<String>) : TextGenerator {
    private var callIndex = 0
    val calls: MutableList<Pair<String, Float>> = mutableListOf()

    override fun generate(prompt: String, temperature: Float): Flow<String> {
        calls += prompt to temperature
        val idx = if (callIndex < scripts.size) callIndex else scripts.lastIndex
        callIndex++
        return flowOf(scripts[idx])
    }
}
