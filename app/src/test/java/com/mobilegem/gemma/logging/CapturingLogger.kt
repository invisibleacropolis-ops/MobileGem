package com.mobilegem.gemma.logging

/**
 * Test-only [AppLogger] that records every event in memory. Install via
 * `AppLog.install(CapturingLogger())` in `@Before`; uninstall in `@After`
 * with `AppLog.uninstall()` so other tests are unaffected.
 */
class CapturingLogger : AppLogger {
    val entries: MutableList<Entry> = mutableListOf()

    data class Entry(
        val level: LogLevel,
        val category: String,
        val message: String,
        val data: Map<String, Any?>,
        val throwable: Throwable?,
    )

    override fun log(
        level: LogLevel,
        category: String,
        message: String,
        data: Map<String, Any?>,
        throwable: Throwable?,
    ) {
        entries += Entry(level, category, message, data, throwable)
    }

    override fun flush() = Unit

    /** True when at least one entry matches the given category + message. */
    fun contains(category: String, message: String): Boolean =
        entries.any { it.category == category && it.message == message }

    /** Returns all entries matching the given category. */
    fun forCategory(category: String): List<Entry> =
        entries.filter { it.category == category }
}
