package com.grokadile.data.local.entity

import androidx.room.Entity

@Entity(tableName = "agent_memory", primaryKeys = ["agentId", "key"])
data class AgentMemoryEntity(
    val agentId: String,
    val key: String,
    val value: String,
    val updatedAt: Long,
)
