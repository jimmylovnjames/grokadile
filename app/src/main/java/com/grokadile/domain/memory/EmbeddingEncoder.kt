package com.grokadile.domain.memory

import com.grokadile.core.math.VectorMath
import javax.inject.Inject
import javax.inject.Singleton

interface EmbeddingEncoder {
    val dimensions: Int
    fun embed(text: String): FloatArray
}

@Singleton
class HashingEmbeddingEncoder @Inject constructor() : EmbeddingEncoder {
    override val dimensions: Int = DIM

    override fun embed(text: String): FloatArray {
        val vec = FloatArray(DIM)
        val normalized = text.lowercase().trim()
        if (normalized.isEmpty()) return vec

        for (token in TOKEN_SPLIT.split(normalized)) {
            if (token.isBlank()) continue
            addFeature(vec, "t:$token", 1f)
            if (token.length >= 2) {
                for (i in 0 until token.length - 1) {
                    addFeature(vec, "b:${token.substring(i, i + 2)}", 0.5f)
                }
            }
        }
        val compact = normalized.replace("\\s+".toRegex(), " ")
        if (compact.length >= 3) {
            for (i in 0 until compact.length - 2) {
                addFeature(vec, "c:${compact.substring(i, i + 3)}", 0.25f)
            }
        }
        return VectorMath.l2Normalize(vec)
    }

    private fun addFeature(vec: FloatArray, feature: String, weight: Float) {
        val h = feature.hashCode()
        val idx = (h ushr 1) % DIM
        val sign = if (h and 1 == 0) 1f else -1f
        vec[idx] += sign * weight
    }

    companion object {
        const val DIM = 256
        private val TOKEN_SPLIT = Regex("[^a-z0-9_]+")
    }
}
