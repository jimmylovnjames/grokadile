package com.grokadile.agent.builtin

import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.core.common.JsonText
import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.PlannerCatalog
import com.grokadile.domain.model.ChatMessage
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatRole
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import com.grokadile.domain.repository.VectorMemoryRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a natural-language goal into a short sequence of agent tasks via Grok,
 * then enqueues them. This is the "brain" that composes landed capabilities.
 *
 * Payload: `{"goal":"...","dryRun":false,"maxSteps":6,"useMemory":true}`
 */
@Singleton
class PlannerAgent @Inject constructor(
    private val json: Json,
    private val memoryStore: VectorMemoryRepository,
) : Agent {

    @Serializable
    data class Payload(
        val goal: String = "",
        val model: String? = null,
        val maxSteps: Int = 6,
        val dryRun: Boolean = false,
        val useMemory: Boolean = true,
    )

    @Serializable
    data class PlanStep(
        val agentId: String,
        val title: String = "",
        val payload: JsonElement = JsonObject(emptyMap()),
        val priority: String = "NORMAL",
    )

    @Serializable
    data class PlanDto(
        val steps: List<PlanStep> = emptyList(),
        val summary: String? = null,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Planner",
        description = "Ask Grok to break a goal into agent tasks and enqueue them.",
        capabilities = setOf(AgentCapability.NETWORK, AgentCapability.BACKGROUND),
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse {
                if (task.payload.isNotBlank() && !task.payload.trimStart().startsWith("{")) {
                    return plan(Payload(goal = task.payload), context)
                }
                return AgentResult.failure("Invalid planner payload: ${it.message}", it)
            }
        val goal = payload.goal.trim()
        if (goal.isBlank()) return AgentResult.failure("goal required")
        return plan(payload.copy(goal = goal), context)
    }

    private suspend fun plan(payload: Payload, context: AgentContext): AgentResult {
        val memories = if (payload.useMemory) {
            runCatching { memoryStore.search(payload.goal, limit = 4, minScore = 0.08f) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val memoryBlock = if (memories.isEmpty()) {
            "(none)"
        } else {
            memories.joinToString("\n") { "- ${it.item.text.take(200)}" }
        }

        val request = ChatRequest(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(
                    ChatRole.USER,
                    "Goal: ${payload.goal}\nRelevant memories:\n$memoryBlock",
                ),
            ),
            model = payload.model ?: DEFAULT_MODEL,
            temperature = 0.2,
            maxTokens = 800,
        )

        return when (val result = context.grok.chat(request)) {
            is AppResult.Success -> applyPlan(result.data.content, payload, context)
            is AppResult.Failure -> when (val error = result.error) {
                is AppError.Network -> AgentResult.retry("network error: ${error.message}")
                is AppError.Http ->
                    if (error.code == 429 || error.code >= 500) {
                        AgentResult.retry("server error ${error.code}")
                    } else {
                        AgentResult.failure("HTTP ${error.code}: ${error.message}", error.cause)
                    }
                else -> AgentResult.failure(error.message, error.cause)
            }
        }
    }

    private suspend fun applyPlan(
        raw: String,
        payload: Payload,
        context: AgentContext,
    ): AgentResult {
        val parsed = parsePlan(raw, json)
            ?: return AgentResult.failure("Planner could not parse a step list from Grok")
        val cap = payload.maxSteps.coerceIn(1, MAX_STEPS)
        val accepted = parsed.steps
            .filter { it.agentId in PlannerCatalog.DISPATCHABLE_IDS }
            .take(cap)
        val skipped = parsed.steps.size - accepted.size

        if (accepted.isEmpty()) {
            return AgentResult.failure(
                "No dispatchable steps. Allowed: ${PlannerCatalog.DISPATCHABLE_IDS.joinToString()}",
            )
        }

        val ids = mutableListOf<String>()
        if (!payload.dryRun) {
            for (step in accepted) {
                val priority = runCatching { TaskPriority.valueOf(step.priority.uppercase()) }
                    .getOrDefault(TaskPriority.NORMAL)
                val body = encodePayload(step.payload)
                val id = context.enqueue(
                    Task(
                        agentId = step.agentId,
                        title = step.title.ifBlank { "Plan: ${step.agentId}" },
                        payload = body,
                        priority = priority,
                    ),
                )
                ids += id
            }
        }

        val summary = parsed.summary?.takeIf { it.isNotBlank() }
            ?: accepted.joinToString(" → ") { it.agentId }
        val report = buildString {
            append(if (payload.dryRun) "dry-run " else "")
            append("${accepted.size} step(s)")
            if (skipped > 0) append(" ($skipped skipped)")
            append(": ")
            append(summary)
            append('\n')
            accepted.forEachIndexed { i, step ->
                append("${i + 1}. ${step.agentId}")
                if (step.title.isNotBlank()) append(" — ${step.title}")
                append('\n')
            }
            if (ids.isNotEmpty()) append("enqueued=${ids.joinToString()}")
        }.trim()
        context.memory.put("last_plan", report)
        context.logger.i("plan ${accepted.size} step(s) dryRun=${payload.dryRun}")
        return AgentResult.success(report)
    }

    private fun encodePayload(element: JsonElement): String = when (element) {
        is JsonPrimitive -> if (element.isString) element.content else element.toString()
        else -> json.encodeToString(JsonElement.serializer(), element)
    }

    companion object {
        const val ID = "planner"
        private const val DEFAULT_MODEL = "grok-2-latest"
        private const val MAX_STEPS = 8

        private val SYSTEM_PROMPT = """
You are Grokadile's on-device planner. Decompose the user goal into a SHORT list of agent tasks.
Return JSON only — no markdown, no prose:
{"summary":"one line","steps":[{"agentId":"...","title":"...","payload":{...},"priority":"NORMAL"}]}

Rules:
- 1–6 steps. Prefer the smallest plan that works.
- Only use these agentId values: ${PlannerCatalog.DISPATCHABLE_IDS.joinToString()}
- payload must be valid for that agent.
- Do not include the planner agent itself.
- If the goal is just a question, use a single grok.chat step.
- If the goal is to remember something, use vector_memory remember.
${PlannerCatalog.SCHEMA_HINT}
""".trimIndent()

        fun parsePlan(raw: String, json: Json): PlanDto? {
            val cleaned = JsonText.extractObject(raw)
            return runCatching { json.decodeFromString<PlanDto>(cleaned) }.getOrNull()
                ?.takeIf { it.steps.isNotEmpty() }
        }
    }
}
