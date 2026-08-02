package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ScreenContentProvider
import com.grokadile.domain.model.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the current screen via the accessibility service and returns a structured
 * dump. This is the foundational observation capability — everything that needs
 * to "see" the phone builds on top of it.
 *
 * Payload (all optional):
 * ```json
 * {
 *   "mode": "hierarchy" | "text" | "focused",   // default hierarchy
 *   "maxDepth": 12,
 *   "maxNodes": 400,
 *   "store": true                                // also write to memory
 * }
 * ```
 *
 * Result is the dump text. Last successful dump is always stored under memory
 * key `last_screen_dump` (and `last_screen_pkg` / `last_screen_title`).
 */
@Singleton
class ScreenReadingAgent @Inject constructor(
    private val screen: ScreenContentProvider,
    private val json: Json,
) : Agent {

    @Serializable
    private data class Payload(
        val mode: String = "hierarchy",
        val maxDepth: Int = 12,
        val maxNodes: Int = 400,
        val store: Boolean = true,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Screen Reader",
        description = "Dumps current screen content (text / hierarchy / focused node) via accessibility.",
        capabilities = setOf(AgentCapability.ACCESSIBILITY),
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

        context.logger.i("Reading screen mode=${payload.mode} depth=${payload.maxDepth}")

        val dump = screen.dump(
            mode = payload.mode,
            maxDepth = payload.maxDepth.coerceIn(1, 30),
            maxNodes = payload.maxNodes.coerceIn(20, 2000),
        )

        if (dump.startsWith("ERROR:")) {
            context.logger.e(dump)
            return AgentResult.failure(dump)
        }

        val pkg = screen.activePackage()
        val title = screen.activeWindowTitle()

        if (payload.store) {
            context.memory.put(KEY_LAST_DUMP, dump)
            context.memory.put(KEY_LAST_PKG, pkg ?: "")
            context.memory.put(KEY_LAST_TITLE, title ?: "")
            context.memory.put(KEY_LAST_TS, System.currentTimeMillis().toString())
        }

        context.logger.i("Screen dump captured (${dump.length} chars) pkg=$pkg")
        return AgentResult.success(dump)
    }

    companion object {
        const val ID = "screen_reader"
        const val KEY_LAST_DUMP = "last_screen_dump"
        const val KEY_LAST_PKG = "last_screen_pkg"
        const val KEY_LAST_TITLE = "last_screen_title"
        const val KEY_LAST_TS = "last_screen_ts"
    }
}
