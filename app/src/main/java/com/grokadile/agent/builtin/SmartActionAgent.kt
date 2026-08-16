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
 * Observe → decide → act in one shot.
 *
 * Dumps the current accessibility tree, asks Grok for a single concrete UI
 * action that advances [Payload.goal], then executes that action via the
 * accessibility action provider. This is the tighter tool-calling loop that
 * follows ScreenSummary (#10).
 *
 * Payload:
 * ```json
 * {
 *   "goal": "tap the Open Settings button",
 *   "mode": "hierarchy",
 *   "maxDepth": 10,
 *   "maxNodes": 250,
 *   "store": true,
 *   "model": "grok-2-latest"
 * }
 * ```
 *
 * Grok is instructed to reply with a single JSON object only:
 * `{"action":"click_text","text":"Settings"}` | `tap`/`swipe`/`type`/`global`/`none`.
 * Markdown fences are stripped if present.
 */
@Singleton
class SmartActionAgent @Inject constructor(
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
        val store: Boolean = true,
        val model: String? = null,
    )

    @Serializable
    data class DecidedAction(
        val action: String = "none",
        val text: String? = null,
        val viewId: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val fromX: Int? = null,
        val fromY: Int? = null,
        val toX: Int? = null,
        val toY: Int? = null,
        val durationMs: Long? = null,
        val name: String? = null,
        val exact: Boolean = false,
        val reason: String? = null,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Smart Action",
        description = "Observe screen, decide one UI action toward a goal, and execute it.",
        capabilities = setOf(AgentCapability.ACCESSIBILITY, AgentCapability.NETWORK),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        if (!screen.isAvailable() || !actions.isAvailable()) {
            context.logger.w("Accessibility service not available")
            return AgentResult.retry(
                reason = "Accessibility service not connected. Enable Grokadile in Settings → Accessibility.",
                backoffMillis = 15_000L,
            )
        }

        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrDefault(Payload())

        val goal = payload.goal.trim()
        if (goal.isBlank()) {
            return AgentResult.failure("smart_action requires a non-empty \"goal\" in the payload")
        }

        context.logger.i("Smart action goal=\"$goal\" mode=${payload.mode}")

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
            append("\n\nReply with one JSON action object only.")
        }

        val request = ChatRequest(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(ChatRole.USER, userContent),
            ),
            model = payload.model ?: DEFAULT_MODEL,
            temperature = 0.2,
            maxTokens = 220,
        )

        val decided: DecidedAction = when (val result = context.grok.chat(request)) {
            is AppResult.Success -> {
                val raw = result.data.content.trim()
                parseAction(raw) ?: return AgentResult.failure(
                    "Could not parse Grok action JSON from: ${raw.take(200)}"
                )
            }
            is AppResult.Failure -> when (val error = result.error) {
                is AppError.Network ->
                    return AgentResult.retry("network error: ${error.message}")
                is AppError.Http ->
                    return if (error.code == 429 || error.code >= 500) {
                        AgentResult.retry("server error ${error.code}")
                    } else {
                        AgentResult.failure("HTTP ${error.code}: ${error.message}", error.cause)
                    }
                else -> return AgentResult.failure(error.message, error.cause)
            }
        }

        val actionKey = decided.action.lowercase().replace("-", "_")
        if (actionKey == "none" || actionKey == "noop" || actionKey == "no_op") {
            val msg = "No action taken: ${decided.reason ?: "goal already satisfied or no safe action"}"
            context.logger.i(msg)
            if (payload.store) {
                context.memory.put(KEY_LAST_ACTION, "none")
                context.memory.put(KEY_LAST_RESULT, msg)
                context.memory.put(KEY_LAST_GOAL, goal)
            }
            return AgentResult.success(msg)
        }

        val execResult = executeAction(decided, actionKey)
        context.logger.i("Executed $actionKey → $execResult")

        if (payload.store) {
            context.memory.put(KEY_LAST_ACTION, actionKey)
            context.memory.put(KEY_LAST_RESULT, execResult)
            context.memory.put(KEY_LAST_GOAL, goal)
            context.memory.put(KEY_LAST_TS, System.currentTimeMillis().toString())
        }

        return if (execResult.startsWith("OK")) {
            AgentResult.success("goal=\"$goal\" action=$actionKey → $execResult")
        } else {
            AgentResult.failure("goal=\"$goal\" action=$actionKey failed: $execResult")
        }
    }

    private fun parseAction(raw: String): DecidedAction? {
        val stripped = raw
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { s ->
                val start = s.indexOf('{')
                val end = s.lastIndexOf('}')
                if (start >= 0 && end > start) s.substring(start, end + 1) else s
            }

        return runCatching { json.decodeFromString<DecidedAction>(stripped) }.getOrNull()
    }

    private fun executeAction(d: DecidedAction, actionKey: String): String = when (actionKey) {
        "tap", "click" -> {
            val x = d.x ?: return "ERROR: tap requires x,y"
            val y = d.y ?: return "ERROR: tap requires x,y"
            actions.tap(x, y, d.durationMs ?: 50L)
        }
        "long_press", "longpress", "long" -> {
            val x = d.x ?: return "ERROR: long_press requires x,y"
            val y = d.y ?: return "ERROR: long_press requires x,y"
            actions.longPress(x, y, d.durationMs ?: 800L)
        }
        "swipe" -> {
            val fx = d.fromX ?: return "ERROR: swipe requires fromX,fromY,toX,toY"
            val fy = d.fromY ?: return "ERROR: swipe requires fromX,fromY,toX,toY"
            val tx = d.toX ?: return "ERROR: swipe requires fromX,fromY,toX,toY"
            val ty = d.toY ?: return "ERROR: swipe requires fromX,fromY,toX,toY"
            actions.swipe(fx, fy, tx, ty, d.durationMs ?: 300L)
        }
        "click_text", "text" -> {
            val t = d.text ?: return "ERROR: click_text requires text"
            actions.clickByText(t, d.exact)
        }
        "click_id", "id" -> {
            val id = d.viewId ?: return "ERROR: click_id requires viewId"
            actions.clickById(id)
        }
        "type", "type_text", "input" -> {
            val t = d.text ?: return "ERROR: type requires text"
            actions.typeText(t)
        }
        "global", "global_action" -> {
            val name = d.name ?: return "ERROR: global requires name (BACK|HOME|RECENTS|...)"
            actions.globalAction(name)
        }
        else -> "ERROR: unknown action \"$actionKey\". Supported: tap, long_press, swipe, click_text, click_id, type, global, none"
    }

    companion object {
        const val ID = "smart_action"
        const val KEY_LAST_ACTION = "last_smart_action"
        const val KEY_LAST_RESULT = "last_smart_action_result"
        const val KEY_LAST_GOAL = "last_smart_action_goal"
        const val KEY_LAST_TS = "last_smart_action_ts"

        private const val DEFAULT_MODEL = "grok-2-latest"
        private const val MAX_DUMP_CHARS = 5_500

        private const val SYSTEM_PROMPT =
            "You are Grokadile's on-device UI controller. You receive an Android accessibility " +
            "dump and a high-level goal. Reply with exactly one JSON object (no markdown, no prose) " +
            "describing the single best next action to advance the goal.\n" +
            "Allowed shapes:\n" +
            "{\"action\":\"click_text\",\"text\":\"visible label\",\"exact\":false}\n" +
            "{\"action\":\"click_id\",\"viewId\":\"submit\"}\n" +
            "{\"action\":\"tap\",\"x\":540,\"y\":1200}\n" +
            "{\"action\":\"long_press\",\"x\":540,\"y\":1200}\n" +
            "{\"action\":\"swipe\",\"fromX\":100,\"fromY\":800,\"toX\":100,\"toY\":200}\n" +
            "{\"action\":\"type\",\"text\":\"hello\"}\n" +
            "{\"action\":\"global\",\"name\":\"BACK\"}  // BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS\n" +
            "{\"action\":\"none\",\"reason\":\"goal already done or no safe action\"}\n" +
            "Prefer click_text over coordinates when a label is visible. Never invent UI that is " +
            "not in the dump. If the goal is already satisfied or you cannot act safely, return none."
    }
}
