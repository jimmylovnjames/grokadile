package com.grokadile.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.grokadile.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

/** One row of the `GROUP BY status` count query. */
data class StatusCount(val status: String, val count: Int)

@Dao
abstract class TaskDao {

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = :status ORDER BY scheduledAt ASC")
    abstract fun observeByStatus(status: String): Flow<List<TaskEntity>>

    @Query("SELECT status, COUNT(*) AS count FROM tasks GROUP BY status")
    abstract fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract suspend fun getById(id: String): TaskEntity?

    @Upsert
    abstract suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query(
        """
        DELETE FROM tasks
        WHERE status IN ('SUCCEEDED', 'CANCELLED')
           OR (status = 'FAILED' AND attempts >= maxAttempts)
        """,
    )
    abstract suspend fun deleteTerminal()

    @Query(
        """
        UPDATE tasks SET status = 'PENDING', updatedAt = :now
        WHERE status = 'RUNNING'
        """,
    )
    abstract suspend fun requeueOrphans(now: Long)

    @Query(
        """
        SELECT * FROM tasks
        WHERE status IN ('PENDING', 'RETRY_SCHEDULED') AND scheduledAt <= :now
        ORDER BY priorityRank DESC, scheduledAt ASC, createdAt ASC
        LIMIT 1
        """,
    )
    protected abstract suspend fun selectNextRunnable(now: Long): TaskEntity?

    @Query("UPDATE tasks SET status = 'RUNNING', updatedAt = :now WHERE id = :id")
    protected abstract suspend fun markRunning(id: String, now: Long)

    /** Atomic dequeue: pick the top runnable task and flip it to RUNNING. */
    @Transaction
    open suspend fun claimNext(now: Long): TaskEntity? {
        val next = selectNextRunnable(now) ?: return null
        markRunning(next.id, now)
        return next.copy(status = "RUNNING", updatedAt = now)
    }
}
