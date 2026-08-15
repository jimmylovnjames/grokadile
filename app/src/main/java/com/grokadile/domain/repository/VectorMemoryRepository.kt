package com.grokadile.domain.repository

import com.grokadile.domain.model.VectorMemoryItem
import com.grokadile.domain.model.VectorSearchHit

interface VectorMemoryRepository {
    suspend fun remember(text: String, source: String = "", tags: String = ""): VectorMemoryItem
    suspend fun search(query: String, limit: Int = 5, minScore: Float = 0.05f): List<VectorSearchHit>
    suspend fun get(id: String): VectorMemoryItem?
    suspend fun forget(id: String): Boolean
    suspend fun clear()
    suspend fun count(): Int
}
