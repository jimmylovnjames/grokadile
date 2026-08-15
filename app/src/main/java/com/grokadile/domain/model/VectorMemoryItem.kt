package com.grokadile.domain.model

import java.util.UUID

data class VectorMemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val embedding: FloatArray,
    val source: String = "",
    val tags: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorMemoryItem) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

data class VectorSearchHit(
    val item: VectorMemoryItem,
    val score: Float,
)
