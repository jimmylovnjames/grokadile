package com.grokadile.domain.model

import java.util.UUID

/** Lifecycle states a queued task moves through. */
enum class TaskStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, RETRY_SCHEDULED }

/** Higher priority tasks are dequeued first. */
enum class TaskPriority { LOW, NORMAL, HIGH }

/**
 * A unit of work handed to an [com.grokadile.domain.agent.Agent] by the
 * orchestration engine. [payload] is an opaque JSON string the target agent
 * knows how to interpret, keeping the queue agent-agnostic.
 */
data class Task(
    val id: String = UUID.randomUUID().toString(),
    val agentId: String,
    val title: String,
    val payload: String = "{}",
    val status: TaskStatus = TaskStatus.PENDING,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val attempts: Int = 0,
    val maxAttempts: Int = 3,
    val scheduledAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val resultData: String? = null,
) {
    val isTerminal: Boolean
        get() = status == TaskStatus.SUCCEEDED ||
            status == TaskStatus.CANCELLED ||
            (status == TaskStatus.FAILED && attempts >= maxAttempts)

    val canRetry: Boolean
        get() = status == TaskStatus.FAILED && attempts < maxAttempts
}
