package com.mobilegem.gemma.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TokenEstimatorTest {

    @Test
    fun emptyStringIsZeroTokens() {
        assertThat(TokenEstimator.estimate("")).isEqualTo(0)
    }

    @Test
    fun shortStringRoundsUp() {
        // 5 chars / 4 = 1.25 → 2
        assertThat(TokenEstimator.estimate("hello")).isEqualTo(2)
    }

    @Test
    fun estimateGrowsWithLength() {
        val short = TokenEstimator.estimate("hi")
        val medium = TokenEstimator.estimate("hello there friend")
        val long = TokenEstimator.estimate("hello there friend, ".repeat(20))
        assertThat(medium).isGreaterThan(short)
        assertThat(long).isGreaterThan(medium)
    }

    @Test
    fun estimateForMessageIncludesPerTurnOverhead() {
        // Per-turn overhead (template + role tag) is at least 4 tokens.
        val plain = TokenEstimator.estimate("hi")
        val asMsg = TokenEstimator.estimateMessage(ChatMessage("user", "hi"))
        assertThat(asMsg).isAtLeast(plain + 4)
    }
}
