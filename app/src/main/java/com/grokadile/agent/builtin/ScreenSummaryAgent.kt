package com.grokadile.agent.builtin

import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ScreenContentProvider
import com.grokadile.domain.model.ChatMessage
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatRole
import com.grokadile.domain.model.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures the current screen via accessibility and asks Grok to summarize it.
 * This is the text-based "vision" step: understand what the user is looking at
 * without a full screenshot pipeline.
 *
 * Payload (all optional):
 * ```json
 * {
 *   "mode": "hierarchy" | "text" | "focused",
 *   "maxDepth": 10,
 *   "maxNodes": 250,
 *   "prompt": "What can I tap to open settings?",
 *   "store": true,
 *   "model": "grok-2-latest"
 * }
 * ```
 *
 * Result is a concise natural-language summary. Last summary is stored under
 * memory keys `last_screen_summary`, `last_screen_summary_pkg`, `last_screen_summary_ts`.
 */
@Singleton
class ScreenSummaryAgent @Inject constructor(
    private val screen: ScreenContentProvider,
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val mode: String = "hierarchy",
        val maxDepth: Int = 10,
        val maxNodes: Int = 250,
        val prompt: String? = null,
        val store: Boolean = true,
        val model: String? = null,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Screen Summary",
        description = "Dumps the current screen and asks Grok to summarize UI, key elements and actions.",
        capabilities = setOf(AgentCapability.ACCESSIBILITY, AgentCapability.NETWORK),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        if (!screen.isAvailable()) {
            context.logger.w("Accessibility service not available")
            return AgentResult.retry(
                reason = "Accessibility service not connected. Enable Grokadile in Settings → Accessibility.",
                backoffMillis = 15_000L,
            )
        }

        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrDefault(Payload())

        context.logger.i("Summarizing screen mode=${payload.mode}")

        val dump = screen.dump(
            mode = payload.mode,
            maxDepth = payload.maxDepth.coerceIn(1, 20),
            maxNodes = payload.maxNodes.coerceIn(30, 800),
        )

        if (dump.startsWith("ERROR:")) {
            context.logger.e(dump)
            return AgentResult.failure(dump)
        }

        val pkg = screen.activePackage()
        val title = screen.activeWindowTitle()
        val userQuestion = payload.prompt?.takeIf { it.isNotBlank() }
            ?: "Summarize what is on this screen. List the main app/package, key visible text and interactive elements, and 2–4 plausible next actions the user could take."

        val system = DEFAULT_SYSTEM
        val userContent = buildString {
            append("Package: ").append(pkg ?: "unknown").append('\n')
            if (!title.isNullOrBlank()) append("Title: ").append(title).append('\n')
            append('\n')
            append("Accessibility dump:\n")
            // Cap dump size to keep prompt reasonable
            append(dump.take(MAX_DUMP_CHARS))
            if (dump.length > MAX_DUMP_CHARS) append("\n… [truncated]")
            append("\n\nUser question: ").append(userQuestion)
        }

        val request = ChatRequest(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, system),
                ChatMessage(ChatRole.USER, userContent),
            ),
            model = payload.model ?: DEFAULT_MODEL,
            temperature = 0.3,
            maxTokens = 500,
        )

        return when (val result = context.grok.chat(request)) {
            is AppResult.Success -> {
                val summary = result.data.content.trim()
                if (payload.store && summary.isNotBlank()) {
                    context.memory.put(KEY_LAST_SUMMARY, summary)
                    context.memory.put(KEY_LAST_PKG, pkg ?: "")
                    context.memory.put(KEY_LAST_TITLE, title ?: "")
                    context.memory.put(KEY_LAST_TS, System.currentTimeMillis().toString())
                }
                context.logger.i("Screen summary ready (${summary.length} chars) pkg=$pkg")
                AgentResult.success(summary)
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
        const val ID = "screen_summary"
        const val KEY_LAST_SUMMARY = "last_screen_summary"
        const val KEY_LAST_PKG = "last_screen_summary_pkg"
        const val KEY_LAST_TITLE = "last_screen_summary_title"
        const val KEY_LAST_TS = "last_screen_summary_ts"

        private const val DEFAULT_MODEL = "grok-2-latest"
        private const val MAX_DUMP_CHARS = 6_000

        private const val DEFAULT_SYSTEM =
            "You are Grokadile's screen analyst. You receive a structured accessibility dump " +
            "of an Android window (package, hierarchy or text nodes). Produce a concise, " +
            "actionable summary for an on-device agent. Prefer short bullet points. " +
            "Do not invent UI elements that are not present in the dump. " +
            "If the dump is empty or error-like, say so clearly."
    }
}
