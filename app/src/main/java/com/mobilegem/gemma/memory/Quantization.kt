package com.mobilegem.gemma.memory

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Symmetric int8 vector quantization. Each vector gets its own scale (the
 * max absolute value), and components are stored as signed bytes in
 * `[-127, 127]`. Memory cost: 1 byte per dim + 4 bytes for the scale,
 * versus 4 bytes per dim raw — a ~4x reduction.
 *
 * Max per-component absolute error is `scale / 127`, which for unit vectors
 * is well below cosine-similarity rounding tolerance.
 */
object Quantization {

    data class Quantized(val bytes: ByteArray, val scale: Float) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Quantized) return false
            return scale == other.scale && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode() * 31 + scale.hashCode()
    }

    fun quantize(vector: FloatArray): Quantized {
        if (vector.isEmpty()) return Quantized(ByteArray(0), 0f)
        val maxAbs = vector.maxOf { abs(it) }
        if (maxAbs == 0f) return Quantized(ByteArray(vector.size), 0f)
        val scale = maxAbs / 127f
        val out = ByteArray(vector.size)
        for (i in vector.indices) {
            val q = (vector[i] / scale).roundToInt().coerceIn(-127, 127)
            out[i] = q.toByte()
        }
        return Quantized(out, scale)
    }

    fun dequantize(bytes: ByteArray, scale: Float): FloatArray {
        val out = FloatArray(bytes.size)
        if (scale == 0f) return out
        for (i in bytes.indices) {
            out[i] = bytes[i].toInt() * scale
        }
        return out
    }
}
