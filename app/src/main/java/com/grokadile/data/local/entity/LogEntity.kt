package com.grokadile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "logs",
    indices = [Index(value = ["timestamp"]), Index(value = ["agentId"])],
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val agentId: String?,
    val taskId: String?,
    val stackTrace: String?,
)
