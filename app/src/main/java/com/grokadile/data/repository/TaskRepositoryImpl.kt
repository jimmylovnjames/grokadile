package com.grokadile.data.repository

import com.grokadile.core.common.DispatcherProvider
import com.grokadile.data.local.dao.TaskDao
import com.grokadile.data.local.mapper.toDomain
import com.grokadile.data.local.mapper.toEntity
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskStatus
import com.grokadile.domain.repository.TaskCounts
import com.grokadile.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val dao: TaskDao,
    private val dispatchers: DispatcherProvider,
) : TaskRepository {

    override fun observeAll(): Flow<List<Task>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeByStatus(status: TaskStatus): Flow<List<Task>> =
        dao.observeByStatus(status.name).map { rows -> rows.map { it.toDomain() } }

    override fun observeCounts(): Flow<TaskCounts> =
        dao.observeStatusCounts().map { rows ->
            val byStatus = rows.associate { it.status to it.count }
            TaskCounts(
                pending = (byStatus[TaskStatus.PENDING.name] ?: 0) +
                    (byStatus[TaskStatus.RETRY_SCHEDULED.name] ?: 0),
                running = byStatus[TaskStatus.RUNNING.name] ?: 0,
                succeeded = byStatus[TaskStatus.SUCCEEDED.name] ?: 0,
                failed = byStatus[TaskStatus.FAILED.name] ?: 0,
            )
        }

    override suspend fun getById(id: String): Task? = withContext(dispatchers.io) {
        dao.getById(id)?.toDomain()
    }

    override suspend fun upsert(task: Task) = withContext(dispatchers.io) {
        dao.upsert(task.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    override suspend fun delete(id: String) = withContext(dispatchers.io) {
        dao.deleteById(id)
    }

    override suspend fun clearTerminal() = withContext(dispatchers.io) {
        dao.deleteTerminal()
    }

    override suspend fun claimNext(now: Long): Task? = withContext(dispatchers.io) {
        dao.claimNext(now)?.toDomain()
    }

    override suspend fun requeueOrphans() = withContext(dispatchers.io) {
        dao.requeueOrphans(System.currentTimeMillis())
    }
}
