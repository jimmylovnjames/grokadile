package com.grokadile.data.repository

import com.grokadile.core.common.DispatcherProvider
import com.grokadile.data.local.dao.LogDao
import com.grokadile.data.local.mapper.toDomain
import com.grokadile.data.local.mapper.toEntity
import com.grokadile.domain.model.LogEntry
import com.grokadile.domain.repository.LogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogRepositoryImpl @Inject constructor(
    private val dao: LogDao,
    private val dispatchers: DispatcherProvider,
) : LogRepository {

    override suspend fun append(entry: LogEntry) = withContext(dispatchers.io) {
        dao.insert(entry.toEntity())
    }

    override fun observeRecent(limit: Int): Flow<List<LogEntry>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeByAgent(agentId: String, limit: Int): Flow<List<LogEntry>> =
        dao.observeByAgent(agentId, limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun clear() = withContext(dispatchers.io) {
        dao.clear()
    }

    override suspend fun trimTo(maxRows: Int) = withContext(dispatchers.io) {
        dao.trimTo(maxRows)
    }
}
