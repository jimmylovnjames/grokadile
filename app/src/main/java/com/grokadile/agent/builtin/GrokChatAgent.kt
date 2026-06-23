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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a prompt to Grok and stores the reply in agent memory. Demonstrates how
 * an agent consumes the shared [AgentContext.grok] gateway and translates
 * transport errors into retry/fail decisions.
 *
 * Expected payload: `{"prompt": "...", "system": "optional", "model": "optional"}`.
 */
@Singleton
class GrokChatAgent @Inject constructor(
    private val json: Json,
) : Agent {

    @Serializable
    private data class Payload(
        val prompt: String,
        val system: String? = null,
        val model: String? = null,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Grok Chat",
        description = "Asks Grok a question and remembers the answer.",
        capabilities = setOf(AgentCapability.NETWORK),
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse { return AgentResult.failure("Invalid payload: ${it.message}", it) }

        val messages = buildList {
            payload.system?.let { add(ChatMessage(ChatRole.SYSTEM, it)) }
            add(ChatMessage(ChatRole.USER, payload.prompt))
        }
        val request = ChatRequest(
            messages = messages,
            model = payload.model ?: DEFAULT_MODEL,
        )

        return when (val result = context.grok.chat(request)) {
            is AppResult.Success -> {
                val reply = result.data.content
                context.memory.put("last_reply", reply)
                context.logger.i("Grok replied with ${reply.length} chars")
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
    }
}
