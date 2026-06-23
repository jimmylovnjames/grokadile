package com.grokadile.domain.repository

import com.grokadile.domain.model.LogEntry
import kotlinx.coroutines.flow.Flow

interface LogRepository {
    suspend fun append(entry: LogEntry)
    fun observeRecent(limit: Int = 500): Flow<List<LogEntry>>
    fun observeByAgent(agentId: String, limit: Int = 500): Flow<List<LogEntry>>
    suspend fun clear()
    /** Cap table growth; keeps only the newest [maxRows] entries. */
    suspend fun trimTo(maxRows: Int)
}
