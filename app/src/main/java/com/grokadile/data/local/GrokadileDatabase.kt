package com.grokadile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.grokadile.data.local.dao.AgentMemoryDao
import com.grokadile.data.local.dao.LogDao
import com.grokadile.data.local.dao.TaskDao
import com.grokadile.data.local.entity.AgentMemoryEntity
import com.grokadile.data.local.entity.LogEntity
import com.grokadile.data.local.entity.TaskEntity

@Database(
    entities = [
        TaskEntity::class,
        LogEntity::class,
        AgentMemoryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class GrokadileDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun logDao(): LogDao
    abstract fun agentMemoryDao(): AgentMemoryDao

    companion object {
        const val NAME = "grokadile.db"
    }
}
