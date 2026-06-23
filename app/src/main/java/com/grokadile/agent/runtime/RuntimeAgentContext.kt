package com.grokadile.agent.runtime

import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentLogger
import com.grokadile.domain.agent.AgentMemory
import com.grokadile.domain.model.Task
import com.grokadile.domain.repository.AgentMemoryRepository
import com.grokadile.domain.repository.GrokRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Concrete [AgentContext] handed to an agent for a single task execution. */
class RuntimeAgentContext(
    override val task: Task,
    override val grok: GrokRepository,
    override val logger: AgentLogger,
    override val memory: AgentMemory,
    private val enqueueFn: suspend (Task) -> String,
    private val isActiveProvider: () -> Boolean,
) : AgentContext {
    override val agentId: String = task.agentId
    override val isActive: Boolean get() = isActiveProvider()
    override suspend fun enqueue(task: Task): String = enqueueFn(task)
}

/**
 * Builds a fresh [RuntimeAgentContext] per task, wiring in the shared services
 * (Grok, persistent memory, structured logging) scoped to that agent/task.
 */
@Singleton
class AgentContextFactory @Inject constructor(
    private val grokLogger: GrokLogger,
    private val memoryRepository: AgentMemoryRepository,
    private val grokRepository: GrokRepository,
) {
    fun create(
        task: Task,
        enqueue: suspend (Task) -> String,
        isActive: () -> Boolean,
    ): AgentContext = RuntimeAgentContext(
        task = task,
        grok = grokRepository,
        logger = ScopedAgentLogger(task.agentId, task.id, grokLogger),
        memory = ScopedAgentMemory(task.agentId, memoryRepository),
        enqueueFn = enqueue,
        isActiveProvider = isActive,
    )
}
