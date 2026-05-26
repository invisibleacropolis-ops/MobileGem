package com.mobilegem.gemma.logging

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes structured log events as JSON lines to a per-launch file under [logsDir].
 *
 * - Each [log] call is non-blocking (events go through an unbounded [Channel]).
 * - A single background coroutine drains the channel and writes lines.
 * - The writer flushes every [flushIntervalMs] ms and on [flush]/[close].
 * - When [enabledProvider] returns false, events are dropped (mirroring to
 *   logcat still happens — that costs nothing and helps device-side debugging).
 *
 * The current log file's path is exposed via [currentFile] for sharing/UX.
 */
class FileLogger(
    logsDir: File,
    private val enabledProvider: () -> Boolean,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val flushIntervalMs: Long = 1500L,
) : AppLogger {

    val currentFile: File

    private val channel = Channel<Entry>(Channel.UNLIMITED)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var closed = false

    init {
        logsDir.mkdirs()
        currentFile = File(logsDir, "mobilegem-${fileNameFormat.format(Date(clock()))}.jsonl")
        scope.launch(Dispatchers.IO) { runWriter() }
        scope.launch(Dispatchers.IO) {
            while (isActive && !closed) {
                delay(flushIntervalMs)
                runCatching { writer?.flush() }
            }
        }
    }

    override fun log(
        level: LogLevel, category: String, message: String,
        data: Map<String, Any?>, throwable: Throwable?,
    ) {
        // Always mirror to logcat regardless of file-logging toggle.
        val tag = "MobileGem"
        val full = if (data.isEmpty()) "[$category] $message"
        else "[$category] $message ${data.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }}"
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, full, throwable)
            LogLevel.INFO -> Log.i(tag, full, throwable)
            LogLevel.WARN -> Log.w(tag, full, throwable)
            LogLevel.ERROR -> Log.e(tag, full, throwable)
        }

        if (!enabledProvider()) return
        if (closed) return
        channel.trySend(Entry(clock(), level, category, message, data, throwable))
    }

    override fun flush() {
        runCatching { writer?.flush() }
    }

    override fun close() {
        closed = true
        // Closing the channel makes the writer coroutine exit its for-loop;
        // the coroutine itself flushes and closes the underlying writer.
        channel.close()
    }

    private suspend fun runWriter() {
        val w = currentFile.bufferedWriter(Charsets.UTF_8).also { writer = it }
        // Header line for context.
        w.write(buildJson {
            put("t", isoFormat.format(Date(clock())))
            put("level", LogLevel.INFO.name)
            put("cat", "log")
            put("msg", "log file opened")
            put("data") {
                put("path", currentFile.absolutePath)
                put("androidVersion", android.os.Build.VERSION.RELEASE)
                put("sdkInt", android.os.Build.VERSION.SDK_INT)
                put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            }
        })
        w.newLine()
        w.flush()
        for (entry in channel) {
            val line = buildJson {
                put("t", isoFormat.format(Date(entry.timestamp)))
                put("level", entry.level.name)
                put("cat", entry.category)
                put("msg", entry.message)
                if (entry.data.isNotEmpty()) {
                    put("data") { entry.data.forEach { (k, v) -> put(k, v?.toString()) } }
                }
                entry.throwable?.let {
                    put("err") {
                        put("type", it.javaClass.name)
                        put("msg", it.message ?: "")
                        put("stack", stackTraceString(it))
                    }
                }
            }
            w.write(line)
            w.newLine()
        }
        runCatching { w.flush() }
        runCatching { w.close() }
    }

    private fun stackTraceString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private data class Entry(
        val timestamp: Long,
        val level: LogLevel,
        val category: String,
        val message: String,
        val data: Map<String, Any?>,
        val throwable: Throwable?,
    )

    // ----- minimal hand-rolled JSON builder (no extra dependency) -----
    private class JsonBuilder {
        private val sb = StringBuilder("{")
        private var first = true
        fun put(key: String, value: String?) {
            sep(); sb.append(quote(key)).append(':').append(if (value == null) "null" else quote(value))
        }
        fun put(key: String, value: Int) {
            sep(); sb.append(quote(key)).append(':').append(value)
        }
        fun put(key: String, block: JsonBuilder.() -> Unit) {
            sep(); sb.append(quote(key)).append(':').append(JsonBuilder().apply(block).build())
        }
        private fun sep() { if (first) first = false else sb.append(',') }
        fun build(): String = sb.append('}').toString()
        private fun quote(s: String): String {
            val out = StringBuilder("\"")
            for (c in s) when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
            return out.append('"').toString()
        }
    }

    private fun buildJson(block: JsonBuilder.() -> Unit): String =
        JsonBuilder().apply(block).build()
}
