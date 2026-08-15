package com.grokadile.agent.builtin

import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.ChatMessage
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatRole
import com.grokadile.domain.model.Task
import com.grokadile.domain.repository.VectorMemoryRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a prompt to Grok, optionally grounded in on-device vector memory, and
 * can remember the Q&A pair afterwards.
 *
 * Expected payload:
 * `{"prompt":"...","system":"...","model":"...","useMemory":true,"remember":true,"memoryLimit":4}`
 */
@Singleton
class GrokChatAgent @Inject constructor(
    private val json: Json,
    private val memoryStore: VectorMemoryRepository,
) : Agent {

    @Serializable
    data class Payload(
        val prompt: String,
        val system: String? = null,
        val model: String? = null,
        val useMemory: Boolean = true,
        val remember: Boolean = true,
        val memoryLimit: Int = 4,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Grok Chat",
        description = "Asks Grok a question, grounded in on-device memory, and can remember the answer.",
        capabilities = setOf(AgentCapability.NETWORK),
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse { return AgentResult.failure("Invalid payload: ${it.message}", it) }

        val memories = if (payload.useMemory) {
            runCatching {
                memoryStore.search(
                    query = payload.prompt,
                    limit = payload.memoryLimit.coerceIn(0, 12),
                    minScore = 0.08f,
                )
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val system = buildString {
            append(payload.system?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM)
            if (memories.isNotEmpty()) {
                append("\n\nRelevant on-device memories (use if helpful, do not mention scores):\n")
                memories.forEachIndexed { i, hit ->
                    append("${i + 1}. ")
                    append(hit.item.text.take(280))
                    append('\n')
                }
            }
        }

        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, system),
            ChatMessage(ChatRole.USER, payload.prompt),
        )
        val request = ChatRequest(
            messages = messages,
            model = payload.model ?: DEFAULT_MODEL,
        )

        return when (val result = context.grok.chat(request)) {
            is AppResult.Success -> {
                val reply = result.data.content
                context.memory.put("last_reply", reply)
                if (payload.remember && reply.isNotBlank()) {
                    runCatching {
                        memoryStore.remember(
                            text = "Q: ${payload.prompt.take(400)}\nA: ${reply.take(800)}",
                            source = ID,
                            tags = "chat",
                        )
                    }
                }
                context.logger.i(
                    "Grok replied with ${reply.length} chars (memories=${memories.size})",
                )
                AgentResult.success(reply)
            }

            is AppResult.Failure -> when (val error = result.error) {
                is AppError.Network ->
                    AgentResult.retry("network error: ${error.message}")
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

    companion object {
        const val ID = "grok.chat"
        private const val DEFAULT_MODEL = "grok-2-latest"
        private const val DEFAULT_SYSTEM =
            "You are Grokadile, a concise on-device assistant. Prefer short, useful answers."
    }
}
