package com.mobilegem.gemma.logging

/**
 * A sink for diagnostic log events. The global [AppLog] facade dispatches here.
 * Implementations must be thread-safe.
 */
interface AppLogger {
    fun log(
        level: LogLevel,
        category: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null,
    )

    /** Force any buffered entries to durable storage. */
    fun flush()

    /** Closes the sink (e.g. on process shutdown). */
    fun close() {}
}

object NoOpLogger : AppLogger {
    override fun log(
        level: LogLevel, category: String, message: String,
        data: Map<String, Any?>, throwable: Throwable?,
    ) = Unit
    override fun flush() = Unit
}
