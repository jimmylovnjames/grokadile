package com.grokadile.domain.agent

import com.grokadile.domain.model.Task
import com.grokadile.domain.repository.GrokRepository

/**
 * Scoped, per-agent logger. Implementations tag entries with the agent/task id
 * and fan out to both Logcat and the persistent log store.
 */
interface AgentLogger {
    fun d(message: String)
    fun i(message: String)
    fun w(message: String, t: Throwable? = null)
    fun e(message: String, t: Throwable? = null)
}

/** Durable per-agent key/value memory. Values are opaque (typically JSON). */
interface AgentMemory {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
    suspend fun keys(): List<String>
}

/**
 * Everything an agent is allowed to touch at runtime. Agents depend only on
 * this surface (never on Hilt or Android), which keeps them portable and
 * trivially testable with fakes.
 */
interface AgentContext {
    val agentId: String

    /** The task currently being executed. */
    val task: Task

    val logger: AgentLogger
    val memory: AgentMemory

    /** Shared access to the Grok LLM backend (via Cloudflare Worker or direct). */
    val grok: GrokRepository

    /** Spawn a follow-up task. Returns the new task id. */
    suspend fun enqueue(task: Task): String

    /** Cooperative cancellation flag; long-running agents should poll this. */
    val isActive: Boolean
}
