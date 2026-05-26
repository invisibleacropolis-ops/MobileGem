package com.mobilegem.gemma.logging

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileLoggerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun writesEntryWhenEnabled() {
        val scope = newScope()
        val logger = FileLogger(
            logsDir = tmp.newFolder("logs"),
            enabledProvider = { true },
            scope = scope,
        )
        logger.log(LogLevel.INFO, "test", "hello world", mapOf("k" to "v"), null)
        // close() is synchronous and drains pending entries before returning.
        logger.close()
        scope.cancel()

        val text = logger.currentFile.readText()
        assertThat(text).contains("\"msg\":\"hello world\"")
        assertThat(text).contains("\"cat\":\"test\"")
        assertThat(text).contains("\"k\":\"v\"")
    }

    @Test
    fun dropsEntriesWhenDisabled() {
        val scope = newScope()
        val logger = FileLogger(
            logsDir = tmp.newFolder("logs"),
            enabledProvider = { false },
            scope = scope,
        )
        logger.log(LogLevel.INFO, "test", "should-not-appear", emptyMap(), null)
        logger.close()
        scope.cancel()

        val text = logger.currentFile.readText()
        assertThat(text).doesNotContain("should-not-appear")
    }

    @Test
    fun closeIsIdempotent() {
        val scope = newScope()
        val logger = FileLogger(
            logsDir = tmp.newFolder("logs"),
            enabledProvider = { true },
            scope = scope,
        )
        logger.log(LogLevel.INFO, "test", "first", emptyMap(), null)
        logger.close()
        logger.close()   // must not throw
        logger.log(LogLevel.INFO, "test", "after-close", emptyMap(), null) // dropped, must not throw
        scope.cancel()

        val text = logger.currentFile.readText()
        assertThat(text).contains("\"msg\":\"first\"")
        assertThat(text).doesNotContain("after-close")
    }
}
