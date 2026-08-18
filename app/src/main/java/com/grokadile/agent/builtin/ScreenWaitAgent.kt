package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.ScreenContentProvider
import com.grokadile.domain.model.Task
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polls the accessibility tree until a condition is met (or timeout).
 *
 * Essential for reliable multi-step UI automation: after a tap or launch,
 * wait for expected text / package before the next action so the chain does
 * not race the UI.
 *
 * Payload examples:
 * ```json
 * { "mode": "appear", "text": "Settings", "timeoutMs": 10000, "pollMs": 400 }
 * { "mode": "disappear", "text": "Loading…", "timeoutMs": 20000 }
 * { "mode": "package", "packageName": "com.android.settings", "timeoutMs": 8000 }
 * ```
 *
 * Result string describes what matched and after how many polls. Last wait
 * outcome is stored under `last_screen_wait`.
 */
@Singleton
class ScreenWaitAgent @Inject constructor(
    private val screen: ScreenContentProvider,
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val mode: String = "appear",
        val text: String? = null,
        val packageName: String? = null,
        val timeoutMs: Long = 15_000L,
        val pollMs: Long = 500L,
        val exact: Boolean = false,
        val maxDepth: Int = 8,
        val maxNodes: Int = 200,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Screen Wait",
        description = "Poll accessibility until text appears/disappears or a package is active. Useful between UI actions.",
        capabilities = setOf(AgentCapability.ACCESSIBILITY),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        if (!screen.isAvailable()) {
            context.logger.w("Accessibility service not available for wait")
            return AgentResult.retry(
                reason = "Accessibility service not connected. Enable Grokadile in Settings → Accessibility.",
                backoffMillis = 15_000L,
            )
        }

        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse { return AgentResult.failure("Invalid payload: ${it.message}", it) }

        val mode = payload.mode.lowercase().replace("-", "_")
        val validationError = when (mode) {
            "appear", "text", "wait_for" ->
                if (payload.text.isNullOrBlank()) "mode=appear requires non-blank text" else null
            "disappear", "gone", "wait_until_gone" ->
                if (payload.text.isNullOrBlank()) "mode=disappear requires non-blank text" else null
            "package", "pkg", "wait_package" ->
                if (payload.packageName.isNullOrBlank()) "mode=package requires non-blank packageName" else null
            else -> "Unknown mode \"$mode\". Supported: appear, disappear, package"
        }
        if (validationError != null) {
            return AgentResult.failure(validationError)
        }

        val timeoutMs = payload.timeoutMs.coerceIn(500L, 120_000L)
        val pollMs = payload.pollMs.coerceIn(100L, 5_000L)
        val deadline = System.currentTimeMillis() + timeoutMs
        var polls = 0

        context.logger.i("Screen wait mode=$mode timeout=${timeoutMs}ms poll=${pollMs}ms")

        while (context.isActive && System.currentTimeMillis() < deadline) {
            polls++
            when (mode) {
                "appear", "text", "wait_for" -> {
                    val needle = payload.text!!
                    val dump = safeDump(payload)
                    if (textMatches(dump, needle, payload.exact)) {
                        val msg = "OK: text \"$needle\" appeared after ${polls} poll(s)"
                        context.memory.put(KEY_LAST, msg)
                        context.logger.i(msg)
                        return AgentResult.success(msg)
                    }
                }
                "disappear", "gone", "wait_until_gone" -> {
                    val needle = payload.text!!
                    val dump = safeDump(payload)
                    if (!textMatches(dump, needle, payload.exact)) {
                        val msg = "OK: text \"$needle\" disappeared after ${polls} poll(s)"
                        context.memory.put(KEY_LAST, msg)
                        context.logger.i(msg)
                        return AgentResult.success(msg)
                    }
                }
                "package", "pkg", "wait_package" -> {
                    val target = payload.packageName!!
                    val current = screen.activePackage()
                    if (current != null && current.equals(target, ignoreCase = true)) {
                        val msg = "OK: package $target active after ${polls} poll(s)"
                        context.memory.put(KEY_LAST, msg)
                        context.logger.i(msg)
                        return AgentResult.success(msg)
                    }
                }
            }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            delay(minOf(pollMs, remaining))
        }

        if (!context.isActive) {
            return AgentResult.failure("Cancelled while waiting")
        }

        val reason = when (mode) {
            "appear", "text", "wait_for" ->
                "Timeout after ${timeoutMs}ms (${polls} polls): text \"${payload.text}\" never appeared"
            "disappear", "gone", "wait_until_gone" ->
                "Timeout after ${timeoutMs}ms (${polls} polls): text \"${payload.text}\" still present"
            else ->
                "Timeout after ${timeoutMs}ms (${polls} polls): package \"${payload.packageName}\" not active (now=${screen.activePackage()})"
        }
        context.memory.put(KEY_LAST, "FAIL: $reason")
        context.logger.w(reason)
        return AgentResult.failure(reason)
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

    companion object {
        const val ID = "screen_wait"
        const val KEY_LAST = "last_screen_wait"
    }
}
