package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import com.grokadile.domain.repository.VectorMemoryRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorMemoryAgent @Inject constructor(
    private val store: VectorMemoryRepository,
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val mode: String = MODE_SEARCH,
        val text: String? = null,
        val query: String? = null,
        val source: String = "",
        val tags: String = "",
        val id: String? = null,
        val limit: Int = 5,
        val minScore: Float = 0.05f,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Vector Memory",
        description = "Store and retrieve long-term semantic memories on-device.",
        capabilities = setOf(AgentCapability.BACKGROUND),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse {
                if (task.payload.isNotBlank() && !task.payload.trimStart().startsWith("{")) {
                    return remember(Payload(mode = MODE_REMEMBER, text = task.payload), context)
                }
                return AgentResult.failure("Invalid vector_memory payload: ${it.message}", it)
            }

        return when (payload.mode.lowercase()) {
            MODE_REMEMBER -> remember(payload, context)
            MODE_SEARCH -> search(payload, context)
            MODE_FORGET -> forget(payload, context)
            MODE_STATS -> stats(context)
            MODE_CLEAR -> {
                store.clear()
                context.logger.i("vector memory cleared")
                AgentResult.success("cleared")
            }
            else -> AgentResult.failure(
                "Unknown mode '${payload.mode}'. Use remember|search|forget|stats|clear",
            )
        }
    }

    private suspend fun remember(payload: Payload, context: AgentContext): AgentResult {
        val text = payload.text?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("text required for remember")
        val item = store.remember(text = text, source = payload.source, tags = payload.tags)
        context.logger.i("remembered ${item.id} (${text.take(80)})")
        return AgentResult.success(
            """{"id":"${item.id}","source":"${item.source}","tags":"${item.tags}","chars":${text.length}}""",
        )
    }

    private suspend fun search(payload: Payload, context: AgentContext): AgentResult {
        val query = payload.query?.takeIf { it.isNotBlank() }
            ?: payload.text?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("query required for search")
        val hits = store.search(query, limit = payload.limit, minScore = payload.minScore)
        if (hits.isEmpty()) {
            return AgentResult.success("(no matches for \"$query\")")
        }
        val body = hits.mapIndexed { i, h ->
            val preview = h.item.text.replace('\n', ' ').take(160)
            "#${i + 1} score=${"%.3f".format(h.score)} id=${h.item.id}" +
                (if (h.item.source.isNotBlank()) " src=${h.item.source}" else "") +
                "\n$preview"
        }.joinToString("\n\n")
        context.logger.i("search \"$query\" → ${hits.size} hit(s)")
        return AgentResult.success(body)
    }

    private suspend fun forget(payload: Payload, context: AgentContext): AgentResult {
        val id = payload.id?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("id required for forget")
        val ok = store.forget(id)
        return if (ok) {
            context.logger.i("forgot $id")
            AgentResult.success("forgot $id")
        } else {
            AgentResult.failure("not found: $id")
        }
    }

    private suspend fun stats(context: AgentContext): AgentResult {
        val n = store.count()
        context.logger.i("vector memory count=$n")
        return AgentResult.success("count=$n")
    }

    companion object {
        const val ID = "vector_memory"
        const val MODE_REMEMBER = "remember"
        const val MODE_SEARCH = "search"
        const val MODE_FORGET = "forget"
        const val MODE_STATS = "stats"
        const val MODE_CLEAR = "clear"
    }
}
