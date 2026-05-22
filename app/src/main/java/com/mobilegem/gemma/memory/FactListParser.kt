package com.mobilegem.gemma.memory

import kotlinx.serialization.json.Json

object FactListParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Extracts the first top-level JSON string array from [raw]; tolerant of surrounding prose. */
    fun parse(raw: String): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val slice = raw.substring(start, end + 1)
        return runCatching { json.decodeFromString<List<String>>(slice) }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
