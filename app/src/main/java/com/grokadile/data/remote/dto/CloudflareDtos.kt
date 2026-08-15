package com.grokadile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(
    val status: String,
    val version: String? = null,
    val time: Long? = null,
)

@Serializable
data class RemoteTaskDto(
    val id: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    val payload: String = "{}",
    val priority: String = "NORMAL",
    @SerialName("target_device_id") val targetDeviceId: String? = null,
    @SerialName("claimed_by") val claimedBy: String? = null,
)

@Serializable
data class AgentReportDto(
    @SerialName("agent_id") val agentId: String,
    @SerialName("task_id") val taskId: String? = null,
    val status: String,
    val detail: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    @SerialName("device_id") val deviceId: String? = null,
)

@Serializable
data class DeviceHeartbeatRequest(
    @SerialName("device_id") val deviceId: String,
    val label: String = "",
    val agents: List<String> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
)

@Serializable
data class DeviceInfoDto(
    @SerialName("device_id") val deviceId: String,
    val label: String = "",
    val agents: List<String> = emptyList(),
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
    val online: Boolean? = null,
)

@Serializable
data class DeviceListDto(
    val count: Int = 0,
    val devices: List<DeviceInfoDto> = emptyList(),
)

@Serializable
data class HeartbeatResponseDto(
    @SerialName("device_id") val deviceId: String,
    val label: String = "",
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
    @SerialName("online_peers") val onlinePeers: Int = 0,
    val peers: List<DeviceInfoDto> = emptyList(),
)

@Serializable
data class EnqueueTaskRequest(
    val title: String,
    val payload: String = "{}",
    val priority: String = "NORMAL",
    val target: String? = null,
    @SerialName("target_device_id") val targetDeviceId: String? = null,
)

@Serializable
data class BroadcastEnqueueResult(
    val mode: String? = null,
    val delivered: Int? = null,
    val tasks: List<RemoteTaskDto>? = null,
    val id: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val title: String? = null,
)
