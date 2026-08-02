package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ScreenActionProvider
import com.grokadile.domain.model.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full phone UI control via accessibility gestures and node actions.
 *
 * This is priority #2 — closes the observation → action loop.
 *
 * Payload examples:
 * ```json
 * { "action": "tap", "x": 540, "y": 1200 }
 * { "action": "long_press", "x": 540, "y": 1200, "durationMs": 900 }
 * { "action": "swipe", "fromX": 100, "fromY": 800, "toX": 100, "toY": 200 }
 * { "action": "click_text", "text": "Login", "exact": false }
 * { "action": "click_id", "viewId": "submit_button" }
 * { "action": "type", "text": "hello world" }
 * { "action": "global", "name": "BACK" }
 * ```
 *
 * Result is the action outcome string. Last result stored under `last_ui_action`.
 */
@Singleton
class ScreenTapAgent @Inject constructor(
    private val actions: ScreenActionProvider,
    private val json: Json,
) : Agent {

    @Serializable
    private data class Payload(
        val action: String = "tap",
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
        val name: String? = null, // for global actions
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Screen Tap / UI Automator",
        description = "Tap, swipe, long-press, click by text/id, type text, and global actions (BACK/HOME/etc).",
        capabilities = setOf(AgentCapability.ACCESSIBILITY),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        if (!actions.isAvailable()) {
            context.logger.w("Accessibility service not available for UI actions")
            return AgentResult.retry(
                reason = "Accessibility service not connected. Enable Grokadile in Settings → Accessibility.",
                backoffMillis = 15_000L,
            )
        }

        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse { return AgentResult.failure("Invalid payload: ${it.message}", it) }

        context.logger.i("UI action=${payload.action}")

        val result = when (payload.action.lowercase().replace("-", "_")) {
            "tap", "click" -> {
                val x = payload.x ?: return AgentResult.failure("tap requires x,y")
                val y = payload.y ?: return AgentResult.failure("tap requires x,y")
                actions.tap(x, y, payload.durationMs ?: 50L)
            }
            "long_press", "longpress", "long" -> {
                val x = payload.x ?: return AgentResult.failure("long_press requires x,y")
                val y = payload.y ?: return AgentResult.failure("long_press requires x,y")
                actions.longPress(x, y, payload.durationMs ?: 800L)
            }
            "swipe" -> {
                val fx = payload.fromX ?: return AgentResult.failure("swipe requires fromX,fromY,toX,toY")
                val fy = payload.fromY ?: return AgentResult.failure("swipe requires fromX,fromY,toX,toY")
                val tx = payload.toX ?: return AgentResult.failure("swipe requires fromX,fromY,toX,toY")
                val ty = payload.toY ?: return AgentResult.failure("swipe requires fromX,fromY,toX,toY")
                actions.swipe(fx, fy, tx, ty, payload.durationMs ?: 300L)
            }
            "click_text", "text" -> {
                val t = payload.text ?: return AgentResult.failure("click_text requires text")
                actions.clickByText(t, payload.exact)
            }
            "click_id", "id" -> {
                val id = payload.viewId ?: return AgentResult.failure("click_id requires viewId")
                actions.clickById(id)
            }
            "type", "type_text", "input" -> {
                val t = payload.text ?: return AgentResult.failure("type requires text")
                actions.typeText(t)
            }
            "global", "global_action" -> {
                val name = payload.name ?: return AgentResult.failure("global requires name (BACK|HOME|RECENTS|...)")
                actions.globalAction(name)
            }
            else -> return AgentResult.failure(
                "Unknown action \"${payload.action}\". " +
                    "Supported: tap, long_press, swipe, click_text, click_id, type, global"
            )
        }

        context.memory.put("last_ui_action", result)
        context.logger.i(result)

        return if (result.startsWith("OK")) {
            AgentResult.success(result)
        } else {
            // Most action failures are permanent for this attempt (wrong coords, missing node).
            // Let the caller decide whether to retry with a different payload.
            AgentResult.failure(result)
        }
    }

    companion object {
        const val ID = "screen_tap"
    }
}
