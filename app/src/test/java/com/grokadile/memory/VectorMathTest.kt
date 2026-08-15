package com.grokadile.memory

import com.grokadile.core.math.VectorMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VectorMathTest {
    @Test
    fun `identical vectors cosine is 1`() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, VectorMath.cosine(v, v), 1e-5f)
    }

    @Test
    fun `orthogonal vectors cosine is 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, VectorMath.cosine(a, b), 1e-5f)
    }

    @Test
    fun `encode decode roundtrip`() {
        val v = floatArrayOf(0.1f, -0.5f, 2.25f, 0f)
        val back = VectorMath.decodeFloats(VectorMath.encodeFloats(v))
        assertArrayEquals(v, back, 1e-6f)
    }

    @Test
    fun `l2 normalize has unit length`() {
        val n = VectorMath.l2Normalize(floatArrayOf(3f, 4f))
        val len = kotlin.math.sqrt((n[0] * n[0] + n[1] * n[1]).toDouble())
        assertEquals(1.0, len, 1e-5)
    }
}
