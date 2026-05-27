package com.mobilegem.gemma.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.mobilegem.gemma.inference.FakeEmbedder
import com.mobilegem.gemma.inference.FakeTextGenerator
import com.mobilegem.gemma.memory.db.MemoryDatabase
import com.mobilegem.gemma.server.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelfLearningExtractorTest {

    private lateinit var db: MemoryDatabase
    private lateinit var ltm: LongTermMemoryRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        ltm = LongTermMemoryRepository(db.memoryDao()) { 1L }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun extractsFactsFromTranscriptAndStoresThemEmbedded() = runTest {
        val modelOutput = """["User prefers Kotlin", "User lives in Berlin"]"""
        val generator = FakeTextGenerator(tokens = listOf(modelOutput))
        val embedder = FakeEmbedder(
            mapOf(
                "User prefers Kotlin" to floatArrayOf(1f, 0f),
                "User lives in Berlin" to floatArrayOf(0f, 1f),
            ),
        )
        val extractor = SelfLearningExtractor(generator, embedder, ltm)

        val stored = extractor.extractAndStore(
            projectId = 2,
            sessionId = 5,
            transcript = listOf(
                ChatMessage("user", "I love Kotlin and I live in Berlin"),
                ChatMessage("assistant", "Noted!"),
            ),
        )

        assertThat(stored.map { it.content })
            .containsExactly("User prefers Kotlin", "User lives in Berlin")
        val persisted = ltm.entriesForProjectScope(2)
        assertThat(persisted.map { it.content })
            .containsExactly("User prefers Kotlin", "User lives in Berlin")
        assertThat(persisted.first { it.content == "User prefers Kotlin" }.embedding)
            .isEqualTo(floatArrayOf(1f, 0f))
        assertThat(persisted.all { it.sourceSessionId == 5L }).isTrue()
    }

    @Test
    fun storesNothingWhenModelReturnsNoFacts() = runTest {
        val extractor = SelfLearningExtractor(
            FakeTextGenerator(tokens = listOf("No durable facts.")),
            FakeEmbedder(emptyMap()),
            ltm,
        )
        val stored = extractor.extractAndStore(2, 5, listOf(ChatMessage("user", "hi")))
        assertThat(stored).isEmpty()
        assertThat(ltm.entriesForProjectScope(2)).isEmpty()
    }

    @Test
    fun logsRawOutputWhenParseYieldsNothing() = runTest {
        val capturing = com.mobilegem.gemma.logging.CapturingLogger()
        com.mobilegem.gemma.logging.AppLog.install(capturing)
        try {
            val extractor = SelfLearningExtractor(
                generator = FakeTextGenerator(tokens = listOf("Sorry, I couldn't think of any facts.")),
                embedder = FakeEmbedder(emptyMap()),
                ltm = ltm,
            )
            val stored = extractor.extractAndStore(2, 5, listOf(ChatMessage("user", "hi")))
            assertThat(stored).isEmpty()
            val warn = capturing.forCategory("selflearn")
                .firstOrNull { it.message == "parseEmpty" }
            assertThat(warn).isNotNull()
            assertThat(warn!!.data["rawOutput"].toString())
                .contains("Sorry, I couldn't think")
        } finally {
            com.mobilegem.gemma.logging.AppLog.uninstall()
        }
    }

    @Test
    fun retriesOnEmptyParseAndSucceedsOnLaterAttempt() = runTest {
        val generator = com.mobilegem.gemma.inference.ScriptedTextGenerator(
            listOf(
                "I don't have any facts to add.",   // first attempt: empty parse
                """["User likes Kotlin"]""",         // second attempt: success
            ),
        )
        val embedder = FakeEmbedder(mapOf("User likes Kotlin" to floatArrayOf(1f, 0f)))
        val extractor = SelfLearningExtractor(generator, embedder, ltm)

        val stored = extractor.extractAndStore(
            projectId = 2,
            sessionId = 5,
            transcript = listOf(ChatMessage("user", "I love Kotlin")),
        )

        assertThat(stored.map { it.content }).containsExactly("User likes Kotlin")
        assertThat(generator.calls).hasSize(2)
        // Temperatures increase across attempts.
        assertThat(generator.calls[0].second).isLessThan(generator.calls[1].second)
    }

    @Test
    fun givesUpAfterMaxAttempts() = runTest {
        val generator = com.mobilegem.gemma.inference.ScriptedTextGenerator(
            listOf("nope", "still nope", "really nope"),
        )
        val extractor = SelfLearningExtractor(
            generator, FakeEmbedder(emptyMap()), ltm,
        )
        val stored = extractor.extractAndStore(
            2, 5, listOf(ChatMessage("user", "anything")),
        )
        assertThat(stored).isEmpty()
        assertThat(generator.calls).hasSize(3)
    }
}
