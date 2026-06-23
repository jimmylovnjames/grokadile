package com.grokadile.testutil

import com.grokadile.core.common.AppResult
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentLogger
import com.grokadile.domain.agent.AgentMemory
import com.grokadile.domain.model.ChatResponse
import com.grokadile.domain.model.Task
import com.grokadile.domain.repository.GrokRepository

/** In-memory [AgentMemory] for tests. */
class FakeAgentMemory : AgentMemory {
    val store = linkedMapOf<String, String>()
    override suspend fun get(key: String): String? = store[key]
    override suspend fun put(key: String, value: String) { store[key] = value }
    override suspend fun remove(key: String) { store.remove(key) }
    override suspend fun keys(): List<String> = store.keys.toList()
}

object NoopAgentLogger : AgentLogger {
    override fun d(message: String) = Unit
    override fun i(message: String) = Unit
    override fun w(message: String, t: Throwable?) = Unit
    override fun e(message: String, t: Throwable?) = Unit
}

class FakeGrokRepository(
    private val response: AppResult<ChatResponse>,
) : GrokRepository {
    override suspend fun chat(request: com.grokadile.domain.model.ChatRequest) = response
}

/** Minimal [AgentContext] for exercising agents in pure JVM tests. */
class FakeAgentContext(
    override val task: Task,
    override val grok: GrokRepository = FakeGrokRepository(
        AppResult.Success(ChatResponse(content = "ok", model = "grok-test")),
    ),
    val fakeMemory: FakeAgentMemory = FakeAgentMemory(),
) : AgentContext {
    val enqueued = mutableListOf<Task>()
    override val agentId: String = task.agentId
    override val logger: AgentLogger = NoopAgentLogger
    override val memory: AgentMemory = fakeMemory
    override val isActive: Boolean = true
    override suspend fun enqueue(task: Task): String {
        enqueued += task
        return task.id
    }
}
