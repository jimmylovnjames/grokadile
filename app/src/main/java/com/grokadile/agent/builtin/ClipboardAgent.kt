package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ClipboardProvider
import com.grokadile.domain.model.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read / write / clear the system clipboard.
 *
 * Payload: `{"mode":"get|set|clear","text":"..."}`. Bare non-JSON text is treated as set.
 */
@Singleton
class ClipboardAgent @Inject constructor(
    private val clipboard: ClipboardProvider,
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val mode: String = MODE_GET,
        val text: String? = null,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Clipboard",
        description = "Get, set, or clear the system clipboard.",
        capabilities = setOf(AgentCapability.DEVICE),
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse {
                if (task.payload.isNotBlank() && !task.payload.trimStart().startsWith("{")) {
                    return set(task.payload, context)
                }
                return AgentResult.failure("Invalid clipboard payload: ${it.message}", it)
            }

        return when (payload.mode.lowercase()) {
            MODE_GET, "read" -> {
                val text = clipboard.getText()
                if (text.isNullOrEmpty()) {
                    context.logger.i("clipboard empty (or unreadable from background)")
                    AgentResult.success("(empty)")
                } else {
                    context.memory.put("last_clipboard", text)
                    context.logger.i("clipboard get ${text.length} chars")
                    AgentResult.success(text)
                }
            }
            MODE_SET, "write", "copy" -> {
                val text = payload.text?.takeIf { it.isNotBlank() }
                    ?: return AgentResult.failure("text required for set")
                set(text, context)
            }
            MODE_CLEAR -> {
                clipboard.clear()
                context.memory.remove("last_clipboard")
                context.logger.i("clipboard cleared")
                AgentResult.success("cleared")
            }
            else -> AgentResult.failure("Unknown mode '${payload.mode}'. Use get|set|clear")
        }
    }

    private suspend fun set(text: String, context: AgentContext): AgentResult {
        clipboard.setText(text)
        context.memory.put("last_clipboard", text)
        context.logger.i("clipboard set ${text.length} chars")
        return AgentResult.success("copied ${text.length} chars")
    }

    companion object {
        const val ID = "clipboard"
        const val MODE_GET = "get"
        const val MODE_SET = "set"
        const val MODE_CLEAR = "clear"
    }
}
