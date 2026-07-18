package com.grokadile.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["status", "priorityRank", "scheduledAt"]),
        Index(value = ["agentId"]),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val title: String,
    val payload: String,
    val status: String,
    /** Numeric rank so `ORDER BY priorityRank DESC` works (HIGH=2,NORMAL=1,LOW=0). */
    val priorityRank: Int,
    val attempts: Int,
    val maxAttempts: Int,
    val scheduledAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String?,
    val resultData: String?,
)
