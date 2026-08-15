package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.DeviceHealth
import com.grokadile.domain.agent.DeviceHealthProvider
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskStatus
import com.grokadile.domain.repository.TaskRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device vitals plus a small watchdog: re-queue failed tasks or prune the queue.
 *
 * Payload: `{"mode":"status|retry_failed|prune","limit":20}`
 */
@Singleton
class DeviceHealthAgent @Inject constructor(
    private val health: DeviceHealthProvider,
    private val tasks: TaskRepository,
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val mode: String = MODE_STATUS,
        val limit: Int = 20,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Device Health",
        description = "Battery, network, storage snapshot; retry failed tasks; prune completed work.",
        capabilities = setOf(AgentCapability.DEVICE, AgentCapability.BACKGROUND),
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrDefault(Payload())

        return when (payload.mode.lowercase()) {
            MODE_STATUS, "snapshot", "health" -> status(context)
            MODE_RETRY -> retryFailed(payload, context)
            MODE_PRUNE, "clear" -> {
                tasks.clearTerminal()
                context.logger.i("pruned terminal tasks")
                AgentResult.success("pruned completed/failed tasks")
            }
            else -> AgentResult.failure("Unknown mode '${payload.mode}'. Use status|retry_failed|prune")
        }
    }

    private suspend fun status(context: AgentContext): AgentResult {
        val snap = health.snapshot()
        val body = format(snap)
        context.memory.put("last_health", body)
        context.logger.i("health battery=${snap.batteryPercent} net=${snap.networkType}")
        return AgentResult.success(body)
    }

    private suspend fun retryFailed(payload: Payload, context: AgentContext): AgentResult {
        val failed = tasks.listByStatus(TaskStatus.FAILED, payload.limit.coerceIn(1, 100))
        if (failed.isEmpty()) return AgentResult.success("no failed tasks")
        val now = System.currentTimeMillis()
        var n = 0
        for (item in failed) {
            if (item.agentId == ID && item.id == context.task.id) continue
            tasks.upsert(
                item.copy(
                    status = TaskStatus.PENDING,
                    attempts = 0,
                    scheduledAt = now,
                    lastError = null,
                    updatedAt = now,
                ),
            )
            n++
        }
        context.logger.i("requeued $n failed task(s)")
        return AgentResult.success("requeued $n failed task(s)")
    }

    companion object {
        const val ID = "device_health"
        const val MODE_STATUS = "status"
        const val MODE_RETRY = "retry_failed"
        const val MODE_PRUNE = "prune"

        fun format(snap: DeviceHealth): String {
            val charge = if (snap.charging) "charging" else "discharging"
            val freeMb = snap.freeStorageBytes / (1024 * 1024)
            val totalMb = snap.totalStorageBytes / (1024 * 1024)
            val net = if (snap.networkConnected) snap.networkType else "offline"
            return buildString {
                appendLine("${snap.label}  sdk=${snap.sdk} (${snap.release})")
                appendLine("id=${snap.deviceId}")
                appendLine("battery=${snap.batteryPercent}% $charge")
                appendLine("network=$net")
                append("storage=${freeMb}MB free / ${totalMb}MB")
            }
        }
    }
}
