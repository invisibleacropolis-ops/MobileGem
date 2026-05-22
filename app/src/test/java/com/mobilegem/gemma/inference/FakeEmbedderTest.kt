package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeEmbedderTest {

    @Test
    fun returnsConfiguredVectorForKnownTextAndRecordsCalls() = runTest {
        val embedder = FakeEmbedder(
            mapOf("hello" to floatArrayOf(1f, 0f), "world" to floatArrayOf(0f, 1f)),
        )
        assertThat(embedder.embed("hello").toList()).containsExactly(1f, 0f).inOrder()
        assertThat(embedder.embeddedTexts).containsExactly("hello")
    }

    @Test
    fun returnsZeroVectorForUnknownText() = runTest {
        val embedder = FakeEmbedder(mapOf("a" to floatArrayOf(1f, 1f)))
        assertThat(embedder.embed("unknown").toList()).containsExactly(0f, 0f).inOrder()
    }
}
