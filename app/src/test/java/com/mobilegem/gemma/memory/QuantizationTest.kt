package com.mobilegem.gemma.memory

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class QuantizationTest {

    @Test
    fun zeroVectorQuantizesAndDequantizesToZero() {
        val v = FloatArray(8)
        val q = Quantization.quantize(v)
        val out = Quantization.dequantize(q.bytes, q.scale)
        assertThat(out.toList()).isEqualTo(v.toList())
    }

    @Test
    fun nonZeroVectorRoundtripsWithinTolerance() {
        val v = floatArrayOf(0.1f, -0.5f, 0.8f, -0.99f, 0.0f, 0.25f)
        val q = Quantization.quantize(v)
        val out = Quantization.dequantize(q.bytes, q.scale)
        for (i in v.indices) {
            val tolerance = 0.01f * (1f + abs(v.maxOf { abs(it) }))
            assertThat(abs(out[i] - v[i])).isLessThan(tolerance)
        }
    }

    @Test
    fun bytesLengthMatchesVectorLength() {
        val v = FloatArray(384) { it / 384f - 0.5f }
        val q = Quantization.quantize(v)
        assertThat(q.bytes.size).isEqualTo(v.size)
    }
}
