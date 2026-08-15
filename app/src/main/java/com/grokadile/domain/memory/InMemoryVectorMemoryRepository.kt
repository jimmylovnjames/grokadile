package com.grokadile.domain.memory

import com.grokadile.core.math.VectorMath
import com.grokadile.domain.model.VectorMemoryItem
import com.grokadile.domain.model.VectorSearchHit
import com.grokadile.domain.repository.VectorMemoryRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryVectorMemoryRepository(
    private val encoder: EmbeddingEncoder = HashingEmbeddingEncoder(),
) : VectorMemoryRepository {
    private val items = ConcurrentHashMap<String, VectorMemoryItem>()

    override suspend fun remember(text: String, source: String, tags: String): VectorMemoryItem {
        val item = VectorMemoryItem(
            id = UUID.randomUUID().toString(),
            text = text,
            embedding = encoder.embed(text),
            source = source,
            tags = tags,
        )
        items[item.id] = item
        return item
    }

    override suspend fun search(query: String, limit: Int, minScore: Float): List<VectorSearchHit> {
        val q = encoder.embed(query)
        return items.values
            .map { VectorSearchHit(it, VectorMath.cosine(q, it.embedding)) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit.coerceAtLeast(1))
    }

    override suspend fun get(id: String): VectorMemoryItem? = items[id]

    override suspend fun forget(id: String): Boolean = items.remove(id) != null

    override suspend fun clear() {
        items.clear()
    }

    override suspend fun count(): Int = items.size
}
