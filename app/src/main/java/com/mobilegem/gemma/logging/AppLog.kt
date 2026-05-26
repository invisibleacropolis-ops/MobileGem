package com.mobilegem.gemma.logging

/**
 * Global logging facade. Stays a no-op until [install] is called (in
 * [com.mobilegem.gemma.GemmaApp.onCreate]). All methods are safe to call from
 * any thread and from places that have not been wired through DI.
 */
object AppLog {

    @Volatile
    private var impl: AppLogger = NoOpLogger

    fun install(logger: AppLogger) {
        impl = logger
    }

    fun uninstall() {
        impl = NoOpLogger
    }

    fun debug(category: String, message: String, vararg data: Pair<String, Any?>) =
        impl.log(LogLevel.DEBUG, category, message, data.toMap())

    fun event(category: String, message: String, vararg data: Pair<String, Any?>) =
        impl.log(LogLevel.INFO, category, message, data.toMap())

    fun warn(category: String, message: String, vararg data: Pair<String, Any?>) =
        impl.log(LogLevel.WARN, category, message, data.toMap())

    fun error(
        category: String, message: String, throwable: Throwable? = null,
        vararg data: Pair<String, Any?>,
    ) = impl.log(LogLevel.ERROR, category, message, data.toMap(), throwable)

    fun flush() = impl.flush()

    fun close() = impl.close()
}
