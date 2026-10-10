package com.vaulthome.app.index

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object Vectors {
    fun fitDim(values: FloatArray, dim: Int = SearchConfig.VECTOR_DIM): FloatArray {
        return when {
            values.size == dim -> values
            values.size > dim -> values.copyOf(dim)
            else -> values
        }
    }

    fun floatsToBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (index in out.indices) {
            out[index] = buffer.float
        }
        return out
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        val length = minOf(left.size, right.size)
        if (length == 0) return 0f
        var dot = 0f
        var leftNorm = 0f
        var rightNorm = 0f
        for (index in 0 until length) {
            val a = left[index]
            val b = right[index]
            dot += a * b
            leftNorm += a * a
            rightNorm += b * b
        }
        if (leftNorm == 0f || rightNorm == 0f) return 0f
        return dot / sqrt(leftNorm * rightNorm)
    }
}
