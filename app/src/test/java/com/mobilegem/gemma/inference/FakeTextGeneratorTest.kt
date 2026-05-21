package com.mobilegem.gemma.inference

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeTextGeneratorTest {

    @Test
    fun emitsConfiguredTokensInOrder() = runTest {
        val gen = FakeTextGenerator(tokens = listOf("Hel", "lo", "!"))
        val collected = gen.generate("ignored prompt", temperature = 0.5f).toList()
        assertThat(collected).containsExactly("Hel", "lo", "!").inOrder()
    }
}
