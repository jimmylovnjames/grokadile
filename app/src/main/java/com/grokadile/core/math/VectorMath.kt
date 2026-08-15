package com.grokadile.core.math

import kotlin.math.sqrt

object VectorMath {
    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm < 1e-12) return v.copyOf()
        return FloatArray(v.size) { i -> (v[i] / norm).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dim mismatch ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        val denom = sqrt(na) * sqrt(nb)
        if (denom < 1e-12) return 0f
        return (dot / denom).toFloat()
    }

    fun encodeFloats(v: FloatArray): ByteArray {
        val out = ByteArray(v.size * 4)
        var i = 0
        for (f in v) {
            val bits = f.toBits()
            out[i++] = (bits shr 24).toByte()
            out[i++] = (bits shr 16).toByte()
            out[i++] = (bits shr 8).toByte()
            out[i++] = bits.toByte()
        }
        return out
    }

    fun decodeFloats(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0)
        val n = bytes.size / 4
        val out = FloatArray(n)
        var i = 0
        for (j in 0 until n) {
            val bits =
                ((bytes[i].toInt() and 0xff) shl 24) or
                    ((bytes[i + 1].toInt() and 0xff) shl 16) or
                    ((bytes[i + 2].toInt() and 0xff) shl 8) or
                    (bytes[i + 3].toInt() and 0xff)
            out[j] = Float.fromBits(bits)
            i += 4
        }
        return out
    }
}
