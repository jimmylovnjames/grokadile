package com.grokadile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.grokadile.data.local.entity.AgentMemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMemoryDao {

    @Upsert
    suspend fun upsert(entry: AgentMemoryEntity)

    @Query("SELECT value FROM agent_memory WHERE agentId = :agentId AND key = :key")
    suspend fun getValue(agentId: String, key: String): String?

    @Query("DELETE FROM agent_memory WHERE agentId = :agentId AND key = :key")
    suspend fun remove(agentId: String, key: String)

    @Query("SELECT key FROM agent_memory WHERE agentId = :agentId ORDER BY key ASC")
    suspend fun keys(agentId: String): List<String>

    @Query("SELECT * FROM agent_memory WHERE agentId = :agentId ORDER BY updatedAt DESC")
    fun observe(agentId: String): Flow<List<AgentMemoryEntity>>
}
