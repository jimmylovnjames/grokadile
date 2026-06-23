package com.grokadile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Shapes for the optional Cloudflare Worker control-plane endpoints. */
@Serializable
data class HealthDto(
    val status: String,
    val version: String? = null,
)

@Serializable
data class RemoteTaskDto(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    val payload: String = "{}",
    val priority: String = "NORMAL",
)

@Serializable
data class AgentReportDto(
    @SerialName("agent_id") val agentId: String,
    @SerialName("task_id") val taskId: String? = null,
    val status: String,
    val detail: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
