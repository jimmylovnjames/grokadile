package com.grokadile.agent.runtime

import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.agent.AgentLogger
import com.grokadile.domain.agent.AgentMemory
import com.grokadile.domain.model.AgentMemoryEntry
import com.grokadile.domain.model.LogLevel
import com.grokadile.domain.repository.AgentMemoryRepository

/** Logger view bound to one agent/task; delegates to the app-wide [GrokLogger]. */
class ScopedAgentLogger(
    private val agentId: String,
    private val taskId: String,
    private val delegate: GrokLogger,
) : AgentLogger {
    private val tag = "agent:$agentId"

    override fun d(message: String) =
        delegate.log(LogLevel.DEBUG, tag, message, agentId, taskId)

    override fun i(message: String) =
        delegate.log(LogLevel.INFO, tag, message, agentId, taskId)

    override fun w(message: String, t: Throwable?) =
        delegate.log(LogLevel.WARN, tag, message, agentId, taskId, t)

    override fun e(message: String, t: Throwable?) =
        delegate.log(LogLevel.ERROR, tag, message, agentId, taskId, t)
}

/** Memory view that namespaces all keys under one agent id. */
class ScopedAgentMemory(
    private val agentId: String,
    private val repository: AgentMemoryRepository,
) : AgentMemory {
    override suspend fun get(key: String): String? = repository.get(agentId, key)

    override suspend fun put(key: String, value: String) =
        repository.put(AgentMemoryEntry(agentId = agentId, key = key, value = value))

    override suspend fun remove(key: String) = repository.remove(agentId, key)
    override suspend fun keys(): List<String> = repository.keys(agentId)
}
