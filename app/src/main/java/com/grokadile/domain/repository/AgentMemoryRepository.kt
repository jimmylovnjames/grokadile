package com.grokadile.domain.repository

import com.grokadile.domain.model.AgentMemoryEntry
import kotlinx.coroutines.flow.Flow

interface AgentMemoryRepository {
    suspend fun get(agentId: String, key: String): String?
    suspend fun put(entry: AgentMemoryEntry)
    suspend fun remove(agentId: String, key: String)
    suspend fun keys(agentId: String): List<String>
    fun observe(agentId: String): Flow<List<AgentMemoryEntry>>
}
