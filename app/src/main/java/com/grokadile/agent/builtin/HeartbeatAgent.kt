package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A self-perpetuating agent: each run records a beat and enqueues its own next
 * run after [Payload.intervalMillis]. Demonstrates durable, autonomous behavior
 * — once seeded it keeps the orchestrator busy indefinitely until cancelled.
 *
 * Payload (all optional): `{"intervalMillis": 60000, "count": 0}`.
 */
@Singleton
class HeartbeatAgent @Inject constructor(
    private val json: Json,
) : Agent {

    @Serializable
    private data class Payload(
        val intervalMillis: Long = 60_000L,
        val count: Int = 0,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Heartbeat",
        description = "Emits a periodic heartbeat and reschedules itself.",
        capabilities = setOf(AgentCapability.BACKGROUND),
        enabledByDefault = false,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrDefault(Payload())

        val beat = payload.count + 1
        context.logger.i("♥ heartbeat #$beat")
        context.memory.put("beats", beat.toString())

        // Re-arm the next beat.
        val next = payload.copy(count = beat)
        context.enqueue(
            Task(
                agentId = ID,
                title = "Heartbeat #${beat + 1}",
                payload = json.encodeToString(next),
                priority = TaskPriority.LOW,
                scheduledAt = System.currentTimeMillis() + payload.intervalMillis,
            ),
        )
        return AgentResult.success("beat $beat")
    }

    companion object {
        const val ID = "heartbeat"
    }
}
