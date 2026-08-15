package com.grokadile.agent.builtin

import com.grokadile.core.device.DeviceInfoProvider
import com.grokadile.data.remote.api.CloudflareApi
import com.grokadile.data.remote.dto.DeviceHeartbeatRequest
import com.grokadile.data.remote.dto.EnqueueTaskRequest
import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SwarmAgent @Inject constructor(
    private val api: CloudflareApi,
    private val deviceIdentity: DeviceInfoProvider,
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val mode: String = MODE_LIST,
        val all: Boolean = false,
        val targetAgentId: String? = null,
        val targetDeviceId: String? = null,
        val title: String = "swarm task",
        val targetPayload: String = "{}",
        val priority: String = "NORMAL",
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Swarm Coordinator",
        description = "List peer devices and broadcast / target tasks across a phone farm.",
        capabilities = setOf(AgentCapability.NETWORK, AgentCapability.BACKGROUND),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse { return AgentResult.failure("Invalid swarm payload: ${it.message}", it) }

        return when (payload.mode.lowercase()) {
            MODE_WHOAMI -> AgentResult.success(
                "device_id=${deviceIdentity.deviceId}\nlabel=${deviceIdentity.label}",
            )
            MODE_HEARTBEAT -> heartbeat(context)
            MODE_LIST -> listPeers(payload, context)
            MODE_BROADCAST -> broadcast(payload, context)
            MODE_DISPATCH -> dispatch(payload, context)
            else -> AgentResult.failure(
                "Unknown mode '${payload.mode}'. Use whoami|heartbeat|list|broadcast|dispatch",
            )
        }
    }

    private suspend fun heartbeat(context: AgentContext): AgentResult =
        runCatching {
            val resp = api.deviceHeartbeat(
                DeviceHeartbeatRequest(
                    deviceId = deviceIdentity.deviceId,
                    label = deviceIdentity.label,
                    agents = emptyList(),
                    meta = deviceIdentity.meta(),
                ),
            )
            val body = buildString {
                append("heartbeat ok · online_peers=${resp.onlinePeers}")
                if (resp.peers.isNotEmpty()) {
                    append('\n')
                    append(resp.peers.joinToString("\n") { p ->
                        "${p.deviceId} (${p.label.ifBlank { "?" }})"
                    })
                }
            }
            context.logger.i(body)
            AgentResult.success(body)
        }.getOrElse {
            AgentResult.failure("heartbeat failed: ${it.message}", it)
        }

    private suspend fun listPeers(payload: Payload, context: AgentContext): AgentResult =
        runCatching {
            val resp = api.listDevices(all = if (payload.all) "1" else null)
            if (resp.devices.isEmpty()) {
                return AgentResult.success("(no devices seen — are peers heartbeating?)")
            }
            val body = resp.devices.joinToString("\n") { d ->
                val flag = if (d.online == true) "ONLINE" else "seen"
                val agents = if (d.agents.isEmpty()) "" else " agents=${d.agents.joinToString(",")}"
                "[$flag] ${d.deviceId} · ${d.label.ifBlank { "?" }}$agents"
            }
            context.memory.put(KEY_LAST_PEERS, body)
            context.logger.i("swarm list: ${resp.count} device(s)")
            AgentResult.success(body)
        }.getOrElse {
            AgentResult.failure("list devices failed: ${it.message}", it)
        }

    private suspend fun broadcast(payload: Payload, context: AgentContext): AgentResult {
        val agentId = payload.targetAgentId?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("targetAgentId required for broadcast")
        return runCatching {
            val resp = api.enqueueTask(
                agentId,
                EnqueueTaskRequest(
                    title = payload.title,
                    payload = payload.targetPayload,
                    priority = payload.priority,
                    target = "all",
                ),
            )
            val raw = resp.body()?.string().orEmpty()
            if (!resp.isSuccessful) {
                return AgentResult.failure("broadcast HTTP ${resp.code()}: $raw")
            }
            context.logger.i("broadcast → $agentId: $raw")
            AgentResult.success(raw.ifBlank { "broadcast enqueued for $agentId" })
        }.getOrElse {
            AgentResult.failure("broadcast failed: ${it.message}", it)
        }
    }

    private suspend fun dispatch(payload: Payload, context: AgentContext): AgentResult {
        val agentId = payload.targetAgentId?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("targetAgentId required for dispatch")
        val deviceId = payload.targetDeviceId?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("targetDeviceId required for dispatch")
        return runCatching {
            val resp = api.enqueueTask(
                agentId,
                EnqueueTaskRequest(
                    title = payload.title,
                    payload = payload.targetPayload,
                    priority = payload.priority,
                    targetDeviceId = deviceId,
                ),
            )
            val raw = resp.body()?.string().orEmpty()
            if (!resp.isSuccessful) {
                return AgentResult.failure("dispatch HTTP ${resp.code()}: $raw")
            }
            context.logger.i("dispatch → $agentId@$deviceId: $raw")
            AgentResult.success(raw.ifBlank { "dispatched to $deviceId" })
        }.getOrElse {
            AgentResult.failure("dispatch failed: ${it.message}", it)
        }
    }

    companion object {
        const val ID = "swarm"
        const val MODE_WHOAMI = "whoami"
        const val MODE_HEARTBEAT = "heartbeat"
        const val MODE_LIST = "list"
        const val MODE_BROADCAST = "broadcast"
        const val MODE_DISPATCH = "dispatch"
        const val KEY_LAST_PEERS = "swarm_last_peers"
    }
}
