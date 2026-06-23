package com.grokadile.data.repository

import com.grokadile.core.common.DispatcherProvider
import com.grokadile.data.local.dao.AgentMemoryDao
import com.grokadile.data.local.mapper.toDomain
import com.grokadile.data.local.mapper.toEntity
import com.grokadile.domain.model.AgentMemoryEntry
import com.grokadile.domain.repository.AgentMemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentMemoryRepositoryImpl @Inject constructor(
    private val dao: AgentMemoryDao,
    private val dispatchers: DispatcherProvider,
) : AgentMemoryRepository {

    override suspend fun get(agentId: String, key: String): String? =
        withContext(dispatchers.io) { dao.getValue(agentId, key) }

    override suspend fun put(entry: AgentMemoryEntry) = withContext(dispatchers.io) {
        dao.upsert(entry.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    override suspend fun remove(agentId: String, key: String) = withContext(dispatchers.io) {
        dao.remove(agentId, key)
    }

    override suspend fun keys(agentId: String): List<String> =
        withContext(dispatchers.io) { dao.keys(agentId) }

    override fun observe(agentId: String): Flow<List<AgentMemoryEntry>> =
        dao.observe(agentId).map { rows -> rows.map { it.toDomain() } }
}
