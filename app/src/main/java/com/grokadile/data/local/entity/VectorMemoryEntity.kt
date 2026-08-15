package com.grokadile.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vector_memory")
data class VectorMemoryEntity(
    @PrimaryKey val id: String,
    val text: String,
    val embedding: ByteArray,
    val source: String,
    val tags: String,
    val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorMemoryEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
