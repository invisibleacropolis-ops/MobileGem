package com.mobilegem.gemma.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VectorMathTest {

    @Test
    fun identicalVectorsHaveSimilarityOne() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertThat(VectorMath.cosineSimilarity(v, v)).isWithin(1e-6f).of(1f)
    }

    @Test
    fun orthogonalVectorsHaveSimilarityZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertThat(VectorMath.cosineSimilarity(a, b)).isWithin(1e-6f).of(0f)
    }

    @Test
    fun zeroVectorYieldsZeroSimilarity() {
        val a = floatArrayOf(0f, 0f)
        val b = floatArrayOf(1f, 1f)
        assertThat(VectorMath.cosineSimilarity(a, b)).isEqualTo(0f)
    }

    @Test
    fun mismatchedLengthsThrow() {
        val result = runCatching {
            VectorMath.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f))
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
