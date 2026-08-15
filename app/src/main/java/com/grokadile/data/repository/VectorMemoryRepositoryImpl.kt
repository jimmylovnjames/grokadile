package com.grokadile.data.repository

import com.grokadile.core.common.DispatcherProvider
import com.grokadile.core.math.VectorMath
import com.grokadile.data.local.dao.VectorMemoryDao
import com.grokadile.data.local.entity.VectorMemoryEntity
import com.grokadile.domain.memory.EmbeddingEncoder
import com.grokadile.domain.model.VectorMemoryItem
import com.grokadile.domain.model.VectorSearchHit
import com.grokadile.domain.repository.VectorMemoryRepository
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorMemoryRepositoryImpl @Inject constructor(
    private val dao: VectorMemoryDao,
    private val encoder: EmbeddingEncoder,
    private val dispatchers: DispatcherProvider,
) : VectorMemoryRepository {

    override suspend fun remember(text: String, source: String, tags: String): VectorMemoryItem =
        withContext(dispatchers.io) {
            val embedding = encoder.embed(text)
            val item = VectorMemoryItem(
                id = UUID.randomUUID().toString(),
                text = text,
                embedding = embedding,
                source = source,
                tags = tags,
            )
            dao.insert(
                VectorMemoryEntity(
                    id = item.id,
                    text = item.text,
                    embedding = VectorMath.encodeFloats(item.embedding),
                    source = item.source,
                    tags = item.tags,
                    createdAt = item.createdAt,
                ),
            )
            item
        }

    override suspend fun search(query: String, limit: Int, minScore: Float): List<VectorSearchHit> =
        withContext(dispatchers.io) {
            val q = encoder.embed(query)
            dao.getAll()
                .map { row ->
                    val emb = VectorMath.decodeFloats(row.embedding)
                    VectorSearchHit(
                        item = VectorMemoryItem(
                            id = row.id,
                            text = row.text,
                            embedding = emb,
                            source = row.source,
                            tags = row.tags,
                            createdAt = row.createdAt,
                        ),
                        score = VectorMath.cosine(q, emb),
                    )
                }
                .filter { it.score >= minScore }
                .sortedByDescending { it.score }
                .take(limit.coerceAtLeast(1))
        }

    override suspend fun get(id: String): VectorMemoryItem? = withContext(dispatchers.io) {
        dao.getById(id)?.let { row ->
            VectorMemoryItem(
                id = row.id,
                text = row.text,
                embedding = VectorMath.decodeFloats(row.embedding),
                source = row.source,
                tags = row.tags,
                createdAt = row.createdAt,
            )
        }
    }

    override suspend fun forget(id: String): Boolean = withContext(dispatchers.io) {
        dao.delete(id) > 0
    }

    override suspend fun clear() = withContext(dispatchers.io) {
        dao.clear()
    }

    override suspend fun count(): Int = withContext(dispatchers.io) {
        dao.count()
    }
}
