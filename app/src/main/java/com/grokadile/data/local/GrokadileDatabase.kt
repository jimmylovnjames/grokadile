package com.grokadile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.grokadile.data.local.dao.AgentMemoryDao
import com.grokadile.data.local.dao.LogDao
import com.grokadile.data.local.dao.TaskDao
import com.grokadile.data.local.dao.VectorMemoryDao
import com.grokadile.data.local.entity.AgentMemoryEntity
import com.grokadile.data.local.entity.LogEntity
import com.grokadile.data.local.entity.TaskEntity
import com.grokadile.data.local.entity.VectorMemoryEntity

@Database(
    entities = [
        TaskEntity::class,
        LogEntity::class,
        AgentMemoryEntity::class,
        VectorMemoryEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class GrokadileDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun logDao(): LogDao
    abstract fun agentMemoryDao(): AgentMemoryDao
    abstract fun vectorMemoryDao(): VectorMemoryDao

    companion object {
        const val NAME = "grokadile.db"
    }
}
