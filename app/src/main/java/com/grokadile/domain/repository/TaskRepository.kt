package com.grokadile.domain.repository

import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/** Aggregate counts for the dashboard and the foreground notification. */
data class TaskCounts(
    val pending: Int = 0,
    val running: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
) {
    val total: Int get() = pending + running + succeeded + failed
}

interface TaskRepository {
    fun observeAll(): Flow<List<Task>>
    fun observeByStatus(status: TaskStatus): Flow<List<Task>>
    fun observeCounts(): Flow<TaskCounts>

    suspend fun getById(id: String): Task?
    suspend fun upsert(task: Task)
    suspend fun delete(id: String)
    suspend fun clearTerminal()

    /**
     * Atomically pick the highest-priority runnable task (PENDING/RETRY whose
     * [Task.scheduledAt] <= [now]) and flip it to RUNNING. Returns null if none.
     */
    suspend fun claimNext(now: Long = System.currentTimeMillis()): Task?

    /** On startup, re-queue tasks left RUNNING by a killed process. */
    suspend fun requeueOrphans()
}
