package com.mobilegem.gemma.memory

import kotlinx.serialization.json.Json

object FactListParser {

    private val json = Json { ignoreUnknownKeys = true }
    private val bulletRegex = Regex("""^\s*(?:[-*•]|\d+[.)])\s+(.+?)\s*$""")

    /**
     * Extracts facts from model output, tolerant of multiple formats.
     *
     * Strategy:
     * 1. If the text contains a top-level JSON string array, parse and return it.
     * 2. Otherwise, scan line by line for bullets (`-`, `*`, `•`) or numbered
     *    items (`1.`, `2)`), and return their captured text.
     *
     * All results are trimmed; blanks are dropped.
     */
    fun parse(raw: String): List<String> {
        parseJsonArray(raw)?.let { return it }
        return raw.lineSequence()
            .mapNotNull { line -> bulletRegex.matchEntire(line)?.groupValues?.get(1)?.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun parseJsonArray(raw: String): List<String>? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val slice = raw.substring(start, end + 1)
        // A successful JSON parse is authoritative — even if the result is empty.
        // That distinguishes "model deliberately emitted []" (no retry needed) from
        // "no JSON array in the text" (fall back to bullet scan).
        val parsed = runCatching { json.decodeFromString<List<String>>(slice) }.getOrNull()
            ?: return null
        return parsed.map { it.trim() }.filter { it.isNotBlank() }
    }
}
