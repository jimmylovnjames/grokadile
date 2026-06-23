package com.grokadile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.grokadile.data.local.entity.LogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Insert
    suspend fun insert(entry: LogEntity)

    @Query("SELECT * FROM logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LogEntity>>

    @Query(
        """
        SELECT * FROM logs WHERE agentId = :agentId
        ORDER BY timestamp DESC, id DESC LIMIT :limit
        """,
    )
    fun observeByAgent(agentId: String, limit: Int): Flow<List<LogEntity>>

    @Query("DELETE FROM logs")
    suspend fun clear()

    @Query("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY id DESC LIMIT :maxRows)")
    suspend fun trimTo(maxRows: Int)
}
