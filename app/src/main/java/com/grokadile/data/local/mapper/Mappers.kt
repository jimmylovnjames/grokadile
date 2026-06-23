package com.grokadile.data.local.mapper

import com.grokadile.data.local.entity.AgentMemoryEntity
import com.grokadile.data.local.entity.LogEntity
import com.grokadile.data.local.entity.TaskEntity
import com.grokadile.domain.model.AgentMemoryEntry
import com.grokadile.domain.model.LogEntry
import com.grokadile.domain.model.LogLevel
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import com.grokadile.domain.model.TaskStatus

// --- Task ------------------------------------------------------------------

private fun TaskPriority.toRank(): Int = when (this) {
    TaskPriority.LOW -> 0
    TaskPriority.NORMAL -> 1
    TaskPriority.HIGH -> 2
}

private fun Int.toPriority(): TaskPriority = when (this) {
    2 -> TaskPriority.HIGH
    0 -> TaskPriority.LOW
    else -> TaskPriority.NORMAL
}

fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    agentId = agentId,
    title = title,
    payload = payload,
    status = status.name,
    priorityRank = priority.toRank(),
    attempts = attempts,
    maxAttempts = maxAttempts,
    scheduledAt = scheduledAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastError = lastError,
    resultData = resultData,
)

fun TaskEntity.toDomain(): Task = Task(
    id = id,
    agentId = agentId,
    title = title,
    payload = payload,
    status = runCatching { TaskStatus.valueOf(status) }.getOrDefault(TaskStatus.PENDING),
    priority = priorityRank.toPriority(),
    attempts = attempts,
    maxAttempts = maxAttempts,
    scheduledAt = scheduledAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastError = lastError,
    resultData = resultData,
)

// --- Log -------------------------------------------------------------------

fun LogEntry.toEntity(): LogEntity = LogEntity(
    id = id,
    timestamp = timestamp,
    level = level.name,
    tag = tag,
    message = message,
    agentId = agentId,
    taskId = taskId,
    stackTrace = stackTrace,
)

fun LogEntity.toDomain(): LogEntry = LogEntry(
    id = id,
    timestamp = timestamp,
    level = runCatching { LogLevel.valueOf(level) }.getOrDefault(LogLevel.INFO),
    tag = tag,
    message = message,
    agentId = agentId,
    taskId = taskId,
    stackTrace = stackTrace,
)

// --- Agent memory ----------------------------------------------------------

fun AgentMemoryEntry.toEntity(): AgentMemoryEntity = AgentMemoryEntity(
    agentId = agentId,
    key = key,
    value = value,
    updatedAt = updatedAt,
)

fun AgentMemoryEntity.toDomain(): AgentMemoryEntry = AgentMemoryEntry(
    agentId = agentId,
    key = key,
    value = value,
    updatedAt = updatedAt,
)
