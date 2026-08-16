package com.grokadile.agent.builtin

import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ScreenActionProvider
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
 * Goal-directed screen action: dump → Grok decides a single UI action → execute.
 *
 * This is the tighter observation → decision → action loop that follows
 * ScreenSummaryAgent. Useful for voice/chat goals like "tap Login",
 * "open the settings gear", "go back".
 *
 * Payload (goal required):
 * ```json
 * {
 *   "goal": "Open Settings",
 *   "mode": "hierarchy" | "text" | "focused",
 *   "maxDepth": 10,
 *   "maxNodes": 250,
 *   "dryRun": false,
 *   "model": "grok-2-latest"
 * }
 * ```
 *
 * Grok is instructed to reply with a pure JSON action object matching
 * ScreenTapAgent payload shape, or {"action":"none","reason":"..."}.
 * On success the executed (or planned) result is returned and stored under
 * `last_screen_act` / `last_screen_act_goal`.
 */
@Singleton
class ScreenActAgent @Inject constructor(
    private val screen: ScreenContentProvider,
    private val actions: ScreenActionProvider,
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val goal: String = "",
        val mode: String = "hierarchy",
        val maxDepth: Int = 10,
        val maxNodes: Int = 250,
        val dryRun: Boolean = false,
        val model: String? = null,
    )

    @Serializable
    data class PlannedAction(
        val action: String = "none",
        val x: Int? = null,
        val y: Int? = null,
        val fromX: Int? = null,
        val fromY: Int? = null,
        val toX: Int? = null,
        val toY: Int? = null,
        val durationMs: Long? = null,
        val text: String? = null,
        val viewId: String? = null,
        val exact: Boolean = false,
        val name: String? = null,
        val reason: String? = null,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Screen Act",
        description = "Given a natural goal, dump the screen, ask Grok for the best single UI action, then execute it (or dry-run).",
        capabilities = setOf(AgentCapability.ACCESSIBILITY, AgentCapability.NETWORK),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        if (!screen.isAvailable() || !actions.isAvailable()) {
            context.logger.w("Accessibility service not available for screen act")
            return AgentResult.retry(
                reason = "Accessibility service not connected. Enable Grokadile in Settings → Accessibility.",
                backoffMillis = 15_000L,
            )
        }

        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrDefault(Payload())

        val goal = payload.goal.trim().ifBlank {
            return AgentResult.failure("screen_act requires a non-empty \"goal\" string in payload")
        }

        context.logger.i("Screen act goal=\"$goal\" dryRun=${payload.dryRun}")

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

        val userContent = buildString {
            append("Package: ").append(pkg ?: "unknown").append('\n')
            if (!title.isNullOrBlank()) append("Title: ").append(title).append('\n')
            append('\n')
            append("Accessibility dump:\n")
            append(dump.take(MAX_DUMP_CHARS))
            if (dump.length > MAX_DUMP_CHARS) append("\n… [truncated]")
            append("\n\nGoal: ").append(goal)
            append("\n\nReply with ONLY a single JSON object (no markdown, no explanation) describing the best next action.")
        }

        val request = ChatRequest(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(ChatRole.USER, userContent),
            ),
            model = payload.model ?: DEFAULT_MODEL,
            temperature = 0.2,
            maxTokens = 300,
        )

        return when (val result = context.grok.chat(request)) {
            is AppResult.Success -> {
                val raw = result.data.content.trim()
                val planned = parsePlannedAction(raw)
                    ?: return AgentResult.failure("Could not parse Grok action JSON from: ${raw.take(200)}")

                if (planned.action.equals("none", ignoreCase = true)) {
                    val msg = "No action: ${planned.reason ?: "Grok could not find a suitable UI action"}"
                    context.logger.i(msg)
                    context.memory.put(KEY_LAST_ACT, msg)
                    context.memory.put(KEY_LAST_GOAL, goal)
                    return AgentResult.success(msg)
                }

                if (payload.dryRun) {
                    val planStr = json.encodeToString(PlannedAction.serializer(), planned)
                    context.logger.i("Dry-run plan: $planStr")
                    context.memory.put(KEY_LAST_ACT, "dry-run: $planStr")
                    context.memory.put(KEY_LAST_GOAL, goal)
                    return AgentResult.success("DRY-RUN: $planStr")
                }

                val execResult = executePlanned(planned, actions)
                context.memory.put(KEY_LAST_ACT, execResult)
                context.memory.put(KEY_LAST_GOAL, goal)
                context.logger.i(execResult)

                if (execResult.startsWith("OK")) {
                    AgentResult.success(execResult)
                } else {
                    AgentResult.failure(execResult)
                }
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

    private fun parsePlannedAction(raw: String): PlannedAction? {
        // Tolerate markdown fences or leading text
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = raw.substring(start, end + 1)
        return runCatching { json.decodeFromString(PlannedAction.serializer(), candidate) }.getOrNull()
    }

    private fun executePlanned(p: PlannedAction, actions: ScreenActionProvider): String {
        return when (p.action.lowercase().replace("-", "_")) {
            "tap", "click" -> {
                val x = p.x ?: return "FAIL: tap requires x,y"
                val y = p.y ?: return "FAIL: tap requires x,y"
                actions.tap(x, y, p.durationMs ?: 50L)
            }
            "long_press", "longpress", "long" -> {
                val x = p.x ?: return "FAIL: long_press requires x,y"
                val y = p.y ?: return "FAIL: long_press requires x,y"
                actions.longPress(x, y, p.durationMs ?: 800L)
            }
            "swipe" -> {
                val fx = p.fromX ?: return "FAIL: swipe requires fromX,fromY,toX,toY"
                val fy = p.fromY ?: return "FAIL: swipe requires fromX,fromY,toX,toY"
                val tx = p.toX ?: return "FAIL: swipe requires fromX,fromY,toX,toY"
                val ty = p.toY ?: return "FAIL: swipe requires fromX,fromY,toX,toY"
                actions.swipe(fx, fy, tx, ty, p.durationMs ?: 300L)
            }
            "click_text", "text" -> {
                val t = p.text ?: return "FAIL: click_text requires text"
                actions.clickByText(t, p.exact)
            }
            "click_id", "id" -> {
                val id = p.viewId ?: return "FAIL: click_id requires viewId"
                actions.clickById(id)
            }
            "type", "type_text", "input" -> {
                val t = p.text ?: return "FAIL: type requires text"
                actions.typeText(t)
            }
            "global", "global_action" -> {
                val name = p.name ?: return "FAIL: global requires name (BACK|HOME|RECENTS|...)"
                actions.globalAction(name)
            }
            else -> "FAIL: unknown action \"${p.action}\""
        }
    }

    companion object {
        const val ID = "screen_act"
        const val KEY_LAST_ACT = "last_screen_act"
        const val KEY_LAST_GOAL = "last_screen_act_goal"

        private const val DEFAULT_MODEL = "grok-2-latest"
        private const val MAX_DUMP_CHARS = 6_000

        private const val SYSTEM_PROMPT =
            "You are Grokadile's UI action planner. You receive an Android accessibility dump " +
            "and a user goal. Output ONLY a single JSON object (no markdown fences, no prose) " +
            "describing the single best next action. Supported shapes:\n" +
            "{\"action\":\"click_text\",\"text\":\"Login\",\"exact\":false}\n" +
            "{\"action\":\"tap\",\"x\":540,\"y\":1200}\n" +
            "{\"action\":\"long_press\",\"x\":540,\"y\":1200}\n" +
            "{\"action\":\"swipe\",\"fromX\":100,\"fromY\":800,\"toX\":100,\"toY\":200}\n" +
            "{\"action\":\"click_id\",\"viewId\":\"submit\"}\n" +
            "{\"action\":\"type\",\"text\":\"hello\"}\n" +
            "{\"action\":\"global\",\"name\":\"BACK\"}\n" +
            "{\"action\":\"none\",\"reason\":\"no matching element\"}\n" +
            "Prefer click_text when a visible label matches the goal. Never invent elements " +
            "that are absent from the dump. If the goal is already satisfied or impossible, " +
            "return action none."
    }
}
