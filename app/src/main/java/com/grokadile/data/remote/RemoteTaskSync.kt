package com.grokadile.data.remote

import com.grokadile.BuildConfig
import com.grokadile.core.logging.GrokLogger
import com.grokadile.data.remote.api.CloudflareApi
import com.grokadile.data.remote.dto.AgentReportDto
import com.grokadile.data.remote.dto.RemoteTaskDto
import com.grokadile.domain.agent.AgentRegistry
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import com.grokadile.domain.model.TaskStatus
import com.grokadile.domain.repository.TaskRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the Cloudflare Worker control plane to the on-device task queue.
 *
 * - [pullAndEnqueue] polls every registered agent id, maps remote tasks into
 *   local [Task] rows (using the remote UUID as the local id so we can report
 *   back cleanly), and returns how many new tasks were accepted.
 * - [report] posts a status update so the worker can mark the task DONE and
 *   log activity for remote observers.
 *
 * No-ops when CLOUDFLARE_BASE_URL is the placeholder or the network call fails
 * (logged, never throws into the heartbeat / orchestrator).
 */
@Singleton
class RemoteTaskSync @Inject constructor(
    private val api: CloudflareApi,
    private val taskRepository: TaskRepository,
    private val registry: AgentRegistry,
    private val logger: GrokLogger,
) {
    private val enabled: Boolean
        get() {
            val base = BuildConfig.CLOUDFLARE_BASE_URL
            return base.isNotBlank() &&
                !base.contains("example.workers.dev") &&
                !base.contains("localhost")
        }

    /**
     * Pull pending remote tasks for every registered agent and enqueue any
     * that are not already known locally. Safe to call frequently.
     * @return number of newly enqueued tasks
     */
    suspend fun pullAndEnqueue(): Int {
        if (!enabled) return 0

        var accepted = 0
        val agentIds = registry.all().map { it.id }.ifEmpty {
            listOf("screen_reader", "screen_tap", "grok.chat", "echo", "heartbeat")
        }.distinct()

        for (agentId in agentIds) {
            try {
                val remote = api.pullTasks(agentId)
                for (dto in remote) {
                    if (ingest(dto)) accepted++
                }
            } catch (t: Throwable) {
                logger.w(TAG, "pull $agentId failed: ${t.message}", t)
            }
        }

        if (accepted > 0) {
            logger.i(TAG, "Enqueued $accepted remote task(s)")
        }
        return accepted
    }

    /**
     * Report terminal (or intermediate) status for a task back to the worker.
     * Best-effort; failures are logged only.
     */
    suspend fun report(task: Task, status: String, detail: String? = null) {
        if (!enabled) return
        try {
            api.report(
                agentId = task.agentId,
                report = AgentReportDto(
                    agentId = task.agentId,
                    taskId = task.id,
                    status = status,
                    detail = detail ?: task.resultData ?: task.lastError,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        } catch (t: Throwable) {
            logger.w(TAG, "report ${task.id} failed: ${t.message}", t)
        }
    }

    private suspend fun ingest(dto: RemoteTaskDto): Boolean {
        val existing = taskRepository.getById(dto.id)
        if (existing != null) {
            return false
        }

        val priority = when (dto.priority.uppercase()) {
            "HIGH" -> TaskPriority.HIGH
            "LOW" -> TaskPriority.LOW
            else -> TaskPriority.NORMAL
        }

        val task = Task(
            id = dto.id,
            agentId = dto.agentId,
            title = dto.title.ifBlank { "remote:${dto.agentId}" },
            payload = dto.payload.ifBlank { "{}" },
            status = TaskStatus.PENDING,
            priority = priority,
            attempts = 0,
            maxAttempts = 3,
            scheduledAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        taskRepository.upsert(task)
        logger.i(TAG, "← remote task ${dto.id} → ${dto.agentId}: ${dto.title}")
        return true
    }

    companion object {
        private const val TAG = "RemoteSync"
    }
}
