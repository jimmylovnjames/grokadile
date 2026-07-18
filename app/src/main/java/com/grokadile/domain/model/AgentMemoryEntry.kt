package com.grokadile.domain.model

/**
 * A single persisted key/value owned by an agent — its durable "memory" /
 * local state. [value] is an opaque JSON blob the owning agent interprets.
 */
data class AgentMemoryEntry(
    val agentId: String,
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
