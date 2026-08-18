package com.grokadile.agent.builtin

import com.grokadile.core.common.AppError
import com.grokadile.core.common.AppResult
import com.grokadile.core.common.JsonText
import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentLogger
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ScreenActionProvider
import com.grokadile.domain.agent.ScreenContentProvider
import com.grokadile.domain.model.ChatMessage
import com.grokadile.domain.model.ChatRequest
import com.grokadile.domain.model.ChatRole
import com.grokadile.domain.model.Task
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level observe → decide → act → confirm loop.
 *
 * Given a natural-language [Payload.goal], repeatedly:
 * 1. Observes the current screen (accessibility dump today; screenshot+vision later)
 * 2. Asks Grok for the single best next UI action toward the goal
 * 3. Executes it via [ScreenActionProvider] (same surface as [ScreenTapAgent])
 * 4. Optionally waits for an expected change ([ScreenWaitAgent] style)
 * 5. Records the step and repeats until done / max steps / timeout
 *
 * Payload:
 * ```json
 * {
 *   "goal": "Open Settings and turn Wi-Fi off",
 *   "maxSteps": 8,
 *   "timeoutMs": 90000,
 *   "model": "grok-2-latest",
 *   "store": true,
 *   "confirmWithWait": true
 * }
 * ```
 *
 * Last outcome is stored under `last_screen_act`, `last_screen_act_goal`,
 * `last_screen_act_steps`, `last_screen_act_status`, `last_screen_act_ts`.
 *
 * Perception is pluggable: [Payload.perception] `"accessibility"` (default) vs
 * `"vision"`. Vision is not implemented yet and falls back to the text dump;
 * see [ScreenUnderstanding] for the exact swap point.
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
        val maxSteps: Int = 8,
        val timeoutMs: Long = 90_000L,
        val model: String? = null,
        val store: Boolean = true,
        val confirmWithWait: Boolean = true,
        val mode: String = "hierarchy",
        val maxDepth: Int = 10,
        val maxNodes: Int = 250,
        val pollMs: Long = 400L,
        val settleMs: Long = 300L,
        /** `"accessibility"` (text dump) or `"vision"` (screenshot path; falls back today). */
        val perception: String = PERCEPTION_ACCESSIBILITY,
    )

    @Serializable
    data class PlannedAction(
        val status: String = "continue",
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
        val expect: Expect? = null,
        val expectMode: String? = null,
        val expectText: String? = null,
        val expectPackage: String? = null,
        val expectTimeoutMs: Long? = null,
        val expectExact: Boolean = false,
    )

    @Serializable
    data class Expect(
        val mode: String = "appear",
        val text: String? = null,
        val packageName: String? = null,
        val timeoutMs: Long? = null,
        val exact: Boolean = false,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Screen Act",
        description = "Observe the screen, ask Grok for the next UI action toward a goal, execute it, and confirm. Loops until done.",
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
            .getOrElse {
                if (task.payload.isNotBlank() && !task.payload.trimStart().startsWith("{")) {
                    return runLoop(Payload(goal = task.payload.trim()), context)
                }
                return AgentResult.failure("Invalid payload: ${it.message}", it)
            }

        val goal = payload.goal.trim()
        if (goal.isBlank()) {
            return AgentResult.failure("screen_act requires a non-empty \"goal\" string in payload")
        }

        return runLoop(payload.copy(goal = goal), context)
    }

    private suspend fun runLoop(payload: Payload, context: AgentContext): AgentResult {
        val maxSteps = payload.maxSteps.coerceIn(1, 20)
        val timeoutMs = payload.timeoutMs.coerceIn(1_000L, 180_000L)
        val pollMs = payload.pollMs.coerceIn(100L, 5_000L)
        val settleMs = payload.settleMs.coerceIn(0L, 2_000L)
        val deadline = System.currentTimeMillis() + timeoutMs
        val understanding = ScreenUnderstanding(screen)
        val history = mutableListOf<String>()
        var acted = false

        context.logger.i(
            "Screen act goal=\"${payload.goal}\" maxSteps=$maxSteps timeout=${timeoutMs}ms " +
                "perception=${payload.perception} confirm=${payload.confirmWithWait}",
        )

        var step = 0
        while (context.isActive && step < maxSteps && System.currentTimeMillis() < deadline) {
            step++
            val snapshot = understanding.observe(payload, context.logger)
            if (snapshot.dump.startsWith("ERROR:")) {
                return finish(
                    payload, context, history,
                    AgentResult.failure(formatTrail("FAIL: ${snapshot.dump}", history)),
                    status = "error",
                )
            }

            val planned = when (val decided = decide(payload, snapshot, history, context)) {
                is AppResult.Success -> decided.data
                is AppResult.Failure -> {
                    val mapped = mapGrokError(decided.error, acted)
                    return finish(payload, context, history, mapped, status = "error")
                }
            }

            when (val phase = loopStatus(planned)) {
                LoopStatus.DONE -> {
                    val reason = planned.reason?.takeIf { it.isNotBlank() } ?: "goal achieved"
                    history += "$step. done — $reason"
                    context.logger.i("Goal complete at step $step: $reason")
                    return finish(
                        payload, context, history,
                        AgentResult.success(formatTrail("OK: $reason in $step step(s)", history)),
                        status = "success",
                    )
                }
                LoopStatus.STUCK -> {
                    val reason = planned.reason?.takeIf { it.isNotBlank() }
                        ?: "Grok could not find a safe next action"
                    history += "$step. stuck — $reason"
                    context.logger.w("Stuck at step $step: $reason")
                    return finish(
                        payload, context, history,
                        AgentResult.failure(formatTrail("FAIL: stuck: $reason", history)),
                        status = "stuck",
                    )
                }
                LoopStatus.CONTINUE -> {
                    val actionKey = planned.action.lowercase().replace("-", "_")
                    context.logger.i("Step $step action=$actionKey")

                    val execResult = if (actionKey == "wait") {
                        confirmWait(payload, planned, pollMs, deadline, context)
                            ?: "OK: wait skipped (no expect condition)"
                    } else {
                        executePlanned(planned)
                    }
                    acted = acted || !actionKey.startsWith("wait")
                    val detail = actionDetail(planned, actionKey)
                    history += "$step. $detail → $execResult"
                    context.logger.i(history.last())

                    if (payload.confirmWithWait && actionKey != "wait") {
                        val waited = confirmWait(payload, planned, pollMs, deadline, context)
                        if (waited != null) {
                            history += "$step. wait → $waited"
                            context.logger.i(history.last())
                        } else if (settleMs > 0) {
                            delay(settleMs)
                        }
                    } else if (settleMs > 0 && actionKey != "wait") {
                        delay(settleMs)
                    }
                }
            }
        }

        if (!context.isActive) {
            return finish(
                payload, context, history,
                AgentResult.failure(formatTrail("FAIL: cancelled after $step step(s)", history)),
                status = "cancelled",
            )
        }

        val exhausted = step >= maxSteps
        val reason = if (exhausted) {
            "max steps ($maxSteps) reached without completing goal"
        } else {
            "timeout after ${timeoutMs}ms (${step} step(s))"
        }
        context.logger.w(reason)
        return finish(
            payload, context, history,
            AgentResult.failure(formatTrail("FAIL: $reason", history)),
            status = if (exhausted) "max_steps" else "timeout",
        )
    }

    private suspend fun decide(
        payload: Payload,
        snapshot: ScreenSnapshot,
        history: List<String>,
        context: AgentContext,
    ): AppResult<PlannedAction> {
        val userContent = buildString {
            append(snapshot.toPromptBlock())
            append("\n\nGoal: ").append(payload.goal)
            if (history.isEmpty()) {
                append("\n\nNo steps taken yet.")
            } else {
                append("\n\nSteps so far:\n")
                history.forEach { append(it).append('\n') }
            }
            append("\nReply with ONLY a single JSON object (no markdown, no prose).")
        }

        val request = ChatRequest(
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
                ChatMessage(ChatRole.USER, userContent),
            ),
            model = payload.model ?: DEFAULT_MODEL,
            temperature = 0.2,
            maxTokens = 280,
        )

        return when (val result = context.grok.chat(request)) {
            is AppResult.Success -> {
                val raw = result.data.content.trim()
                val planned = parsePlannedAction(raw)
                if (planned == null) {
                    AppResult.Failure(
                        AppError.Serialization("Could not parse Grok action JSON from: ${raw.take(200)}"),
                    )
                } else {
                    AppResult.Success(planned)
                }
            }
            is AppResult.Failure -> result
        }
    }

    private fun parsePlannedAction(raw: String): PlannedAction? {
        val candidate = JsonText.extractObject(raw)
        return runCatching { json.decodeFromString(PlannedAction.serializer(), candidate) }.getOrNull()
    }

    private fun executePlanned(p: PlannedAction): String {
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
            else -> "FAIL: unknown action \"${p.action}\". " +
                "Supported: tap, long_press, swipe, click_text, click_id, type, global, wait, done"
        }
    }

    /**
     * ScreenWait-style poll for the expected post-action change. Returns null
     * when Grok did not specify an expect condition.
     */
    private suspend fun confirmWait(
        payload: Payload,
        planned: PlannedAction,
        pollMs: Long,
        deadline: Long,
        context: AgentContext,
    ): String? {
        val expect = resolveExpect(planned) ?: return null
        val mode = expect.mode.lowercase().replace("-", "_")
        val waitBudget = (expect.timeoutMs ?: 8_000L).coerceIn(200L, 30_000L)
        val waitDeadline = minOf(deadline, System.currentTimeMillis() + waitBudget)
        var polls = 0

        while (context.isActive && System.currentTimeMillis() < waitDeadline) {
            polls++
            val matched = when (mode) {
                "appear", "text", "wait_for" -> {
                    val needle = expect.text ?: return "FAIL: expect.appear requires text"
                    textMatches(safeDump(payload), needle, expect.exact)
                }
                "disappear", "gone", "wait_until_gone" -> {
                    val needle = expect.text ?: return "FAIL: expect.disappear requires text"
                    !textMatches(safeDump(payload), needle, expect.exact)
                }
                "package", "pkg", "wait_package" -> {
                    val target = expect.packageName ?: return "FAIL: expect.package requires packageName"
                    screen.activePackage()?.equals(target, ignoreCase = true) == true
                }
                else -> return "FAIL: unknown expect.mode \"$mode\""
            }
            if (matched) {
                return "OK: confirmed after $polls poll(s)"
            }
            val remaining = waitDeadline - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(minOf(pollMs, remaining))
        }
        return "FAIL: not confirmed after $polls poll(s)"
    }

    private fun resolveExpect(p: PlannedAction): Expect? {
        p.expect?.let { nested ->
            if (!nested.text.isNullOrBlank() || !nested.packageName.isNullOrBlank()) return nested
        }
        val mode = p.expectMode?.takeIf { it.isNotBlank() } ?: "appear"
        val text = p.expectText?.takeIf { it.isNotBlank() }
        val pkg = p.expectPackage?.takeIf { it.isNotBlank() }
        if (text == null && pkg == null) return null
        return Expect(
            mode = mode,
            text = text,
            packageName = pkg,
            timeoutMs = p.expectTimeoutMs,
            exact = p.expectExact,
        )
    }

    private fun safeDump(payload: Payload): String {
        val dump = screen.dump(
            mode = "text",
            maxDepth = payload.maxDepth.coerceIn(1, 16),
            maxNodes = payload.maxNodes.coerceIn(20, 500),
        )
        return if (dump.startsWith("ERROR:")) "" else dump
    }

    private fun textMatches(haystack: String, needle: String, exact: Boolean): Boolean {
        if (needle.isBlank()) return false
        return if (exact) {
            haystack.lines().any { it.trim() == needle }
        } else {
            haystack.contains(needle, ignoreCase = true)
        }
    }

    private fun actionDetail(p: PlannedAction, actionKey: String): String = when (actionKey) {
        "click_text", "text" -> "click_text \"${p.text}\""
        "click_id", "id" -> "click_id ${p.viewId}"
        "type", "type_text", "input" -> "type \"${p.text}\""
        "global", "global_action" -> "global ${p.name}"
        "tap", "click" -> "tap (${p.x},${p.y})"
        "long_press", "longpress", "long" -> "long_press (${p.x},${p.y})"
        "swipe" -> "swipe"
        "wait" -> "wait"
        else -> actionKey
    }

    private fun mapGrokError(error: AppError, acted: Boolean): AgentResult = when (error) {
        is AppError.Network ->
            if (!acted) AgentResult.retry("network error: ${error.message}")
            else AgentResult.failure("network error after UI action(s): ${error.message}", error.cause)
        is AppError.Http ->
            if (error.code == 429 || error.code >= 500) {
                if (!acted) AgentResult.retry("server error ${error.code}")
                else AgentResult.failure("server error ${error.code} after UI action(s)", error.cause)
            } else {
                AgentResult.failure("HTTP ${error.code}: ${error.message}", error.cause)
            }
        is AppError.Serialization,
        is AppError.Storage,
        is AppError.Unknown -> AgentResult.failure(error.message, error.cause)
    }

    private suspend fun finish(
        payload: Payload,
        context: AgentContext,
        history: List<String>,
        result: AgentResult,
        status: String,
    ): AgentResult {
        if (result is AgentResult.Retry) return result
        val output = when (result) {
            is AgentResult.Success -> result.output ?: "OK"
            is AgentResult.Failure -> result.reason
            is AgentResult.Retry -> result.reason
        }
        if (payload.store) {
            context.memory.put(KEY_LAST, output)
            context.memory.put(KEY_LAST_GOAL, payload.goal)
            context.memory.put(KEY_LAST_STEPS, history.size.toString())
            context.memory.put(KEY_LAST_STATUS, status)
            context.memory.put(KEY_LAST_TS, System.currentTimeMillis().toString())
        }
        return result
    }

    private fun formatTrail(headline: String, history: List<String>): String = buildString {
        append(headline)
        if (history.isNotEmpty()) {
            append('\n')
            history.forEach { append(it).append('\n') }
        }
    }.trim()

    private fun loopStatus(p: PlannedAction): LoopStatus {
        val statusKey = p.status.lowercase().replace("-", "_")
        val actionKey = p.action.lowercase().replace("-", "_")
        return when (statusKey) {
            "done", "complete", "success", "goal_complete" -> LoopStatus.DONE
            "stuck", "fail", "failed", "impossible", "abort" -> LoopStatus.STUCK
            else -> when (actionKey) {
                "done", "complete", "success" -> LoopStatus.DONE
                "stuck", "none", "noop", "no_op" -> LoopStatus.STUCK
                else -> LoopStatus.CONTINUE
            }
        }
    }

    private enum class LoopStatus { CONTINUE, DONE, STUCK }

    /**
     * How the agent perceives the current screen.
     *
     * [ACCESSIBILITY_TEXT] is the shipped path (dump + Grok JSON plan).
     * [VISION] is reserved for screenshot → multimodal Grok; [ScreenUnderstanding]
     * falls back to accessibility until that pipeline exists.
     */
    internal enum class PerceptionMode { ACCESSIBILITY_TEXT, VISION }

    /**
     * Snapshot handed to the decide step. Text path fills [dump]; the vision
     * path will additionally fill [description] (and later attach image bytes
     * on [ChatRequest] once the domain model supports multimodal parts).
     */
    internal data class ScreenSnapshot(
        val mode: PerceptionMode,
        val packageName: String?,
        val title: String?,
        val dump: String,
        val description: String = "",
    ) {
        fun toPromptBlock(maxDumpChars: Int = MAX_DUMP_CHARS): String = buildString {
            append("Perception: ").append(mode.name).append('\n')
            append("Package: ").append(packageName ?: "unknown").append('\n')
            if (!title.isNullOrBlank()) append("Title: ").append(title).append('\n')
            if (description.isNotBlank()) {
                append("\nScreen description:\n").append(description).append('\n')
            }
            append("\nAccessibility dump:\n")
            append(dump.take(maxDumpChars))
            if (dump.length > maxDumpChars) append("\n… [truncated]")
        }
    }

    /**
     * Strategy that produces a [ScreenSnapshot]. Swap the VISION branch here
     * without changing the observe → decide → act loop.
     */
    internal class ScreenUnderstanding(
        private val screen: ScreenContentProvider,
    ) {
        fun observe(payload: Payload, logger: AgentLogger): ScreenSnapshot {
            val requested = when (payload.perception.lowercase().replace("-", "_")) {
                "vision", "screenshot", "multimodal", "image" -> PerceptionMode.VISION
                else -> PerceptionMode.ACCESSIBILITY_TEXT
            }
            return when (requested) {
                PerceptionMode.ACCESSIBILITY_TEXT -> observeAccessibility(payload)
                PerceptionMode.VISION -> observeVision(payload, logger)
            }
        }

        private fun observeAccessibility(payload: Payload): ScreenSnapshot {
            val dump = screen.dump(
                mode = payload.mode,
                maxDepth = payload.maxDepth.coerceIn(1, 20),
                maxNodes = payload.maxNodes.coerceIn(30, 800),
            )
            return ScreenSnapshot(
                mode = PerceptionMode.ACCESSIBILITY_TEXT,
                packageName = screen.activePackage(),
                title = screen.activeWindowTitle(),
                dump = dump,
            )
        }

        /**
         * TODO(vision): Plug the screenshot → multimodal Grok path here.
         *
         * When a capture surface exists (MediaProjection / ScreenCaptureProvider):
         *  1. Grab a PNG/JPEG of the current display.
         *  2. Send it as an image content part on ChatRequest (ChatMessage will
         *     need a multimodal content type — do not overload [dump] for bytes).
         *  3. Fill [ScreenSnapshot.description] from the vision reply, or skip a
         *     separate describe call and let [decide] see the image directly.
         *  4. Keep [ScreenSnapshot.dump] as a cheap text grounding signal.
         *
         * Do not implement MediaProjection in this agent. Until that pipeline
         * ships, VISION falls back to accessibility text so the loop still works.
         */
        private fun observeVision(payload: Payload, logger: AgentLogger): ScreenSnapshot {
            logger.w(
                "perception=vision is not implemented yet; falling back to accessibility text",
            )
            return observeAccessibility(payload)
        }
    }

    companion object {
        const val ID = "screen_act"
        const val KEY_LAST = "last_screen_act"
        const val KEY_LAST_GOAL = "last_screen_act_goal"
        const val KEY_LAST_STEPS = "last_screen_act_steps"
        const val KEY_LAST_STATUS = "last_screen_act_status"
        const val KEY_LAST_TS = "last_screen_act_ts"
        const val PERCEPTION_ACCESSIBILITY = "accessibility"
        const val PERCEPTION_VISION = "vision"

        private const val DEFAULT_MODEL = "grok-2-latest"
        private const val MAX_DUMP_CHARS = 6_000

        private const val SYSTEM_PROMPT =
            "You are Grokadile's UI actor. You receive an Android screen snapshot " +
            "(accessibility dump today; later a screenshot description) plus a goal and " +
            "the steps already taken. Output ONLY a single JSON object (no markdown, no prose).\n" +
            "Shapes:\n" +
            "{\"status\":\"continue\",\"action\":\"click_text\",\"text\":\"Login\",\"exact\":false," +
            "\"expectText\":\"Welcome\"}\n" +
            "{\"status\":\"continue\",\"action\":\"tap\",\"x\":540,\"y\":1200}\n" +
            "{\"status\":\"continue\",\"action\":\"long_press\",\"x\":540,\"y\":1200}\n" +
            "{\"status\":\"continue\",\"action\":\"swipe\",\"fromX\":100,\"fromY\":800,\"toX\":100,\"toY\":200}\n" +
            "{\"status\":\"continue\",\"action\":\"click_id\",\"viewId\":\"submit\"}\n" +
            "{\"status\":\"continue\",\"action\":\"type\",\"text\":\"hello\"}\n" +
            "{\"status\":\"continue\",\"action\":\"global\",\"name\":\"BACK\"}\n" +
            "{\"status\":\"continue\",\"action\":\"wait\",\"expectMode\":\"appear\",\"expectText\":\"Done\"}\n" +
            "{\"status\":\"done\",\"action\":\"done\",\"reason\":\"Wi-Fi is already off\"}\n" +
            "{\"status\":\"stuck\",\"action\":\"none\",\"reason\":\"no matching element\"}\n" +
            "Prefer click_text when a visible label matches. Never invent UI that is not in " +
            "the dump. Use history to avoid repeating a failed action. Set status=done when " +
            "the goal is achieved. Set status=stuck only if no safe action remains. " +
            "When the next screen should show specific text after an action, set expectText " +
            "(or expect.packageName) so the device can wait before the next step."
    }
}
