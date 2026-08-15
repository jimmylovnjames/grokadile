package com.grokadile.service

import com.grokadile.agent.AgentController
import com.grokadile.agent.builtin.PlannerAgent
import com.grokadile.agent.builtin.VectorMemoryAgent
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Turns ACTION_SEND text into a remember or plan task. */
@Singleton
class ShareIntake @Inject constructor(
    private val controller: AgentController,
    private val json: Json,
) {
    suspend fun ingest(text: String, subject: String? = null): ShareResult {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return ShareResult(false, "Nothing to share")

        val planPrefix = trimmed.startsWith("plan:", ignoreCase = true)
        val subjectPlan = subject?.contains("plan", ignoreCase = true) == true
        return if (planPrefix || subjectPlan) {
            val goal = trimmed.removePrefix("plan:").removePrefix("Plan:").trim()
            val payload = json.encodeToString(
                PlannerAgent.Payload.serializer(),
                PlannerAgent.Payload(goal = goal.ifBlank { trimmed }),
            )
            val id = controller.enqueue(
                Task(
                    agentId = PlannerAgent.ID,
                    title = "Share: plan",
                    payload = payload,
                    priority = TaskPriority.HIGH,
                ),
            )
            controller.startAutonomous()
            ShareResult(true, "Queued a plan for that share", id)
        } else {
            val payload = buildJsonObject {
                put("mode", VectorMemoryAgent.MODE_REMEMBER)
                put("text", trimmed)
                put("source", "share")
                put("tags", "share")
            }.toString()
            val id = controller.enqueue(
                Task(
                    agentId = VectorMemoryAgent.ID,
                    title = "Share: remember",
                    payload = payload,
                    priority = TaskPriority.HIGH,
                ),
            )
            controller.startAutonomous()
            ShareResult(true, "Saved shared text to memory", id)
        }
    }
}

data class ShareResult(
    val ok: Boolean,
    val message: String,
    val taskId: String? = null,
)
