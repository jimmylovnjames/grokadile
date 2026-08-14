package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Autonomous scheduler. On each run it enqueues a *target* task for the
 * requested agent and then re-arms itself for the next fire time.
 *
 * Supports two schedule kinds (no external cron library):
 *
 * ```json
 * // Interval (every N milliseconds)
 * {
 *   "targetAgentId": "echo",
 *   "targetTitle": "Periodic ping",
 *   "targetPayload": "{\"msg\":\"hi\"}",
 *   "schedule": { "type": "interval", "intervalMillis": 3600000 },
 *   "maxRuns": 24
 * }
 *
 * // Classic 5-field cron (minute hour day-of-month month day-of-week)
 * // Examples: "0 9 * * *" (09:00 daily), "*/15 * * * *" (every 15 min)
 * {
 *   "targetAgentId": "screen_reader",
 *   "targetTitle": "Morning screen dump",
 *   "targetPayload": "{\"mode\":\"hierarchy\"}",
 *   "schedule": { "type": "cron", "expression": "0 8 * * *" },
 *   "enabled": true
 * }
 * ```
 *
 * Optional fields: `targetPriority` ("LOW"|"NORMAL"|"HIGH"), `runCount`
 * (internal), `maxRuns` (null = forever), `enabled` (false stops reschedule).
 */
@Singleton
class SchedulerAgent @Inject constructor(
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val targetAgentId: String,
        val targetTitle: String = "Scheduled task",
        val targetPayload: String = "{}",
        val targetPriority: String = "NORMAL",
        val schedule: Schedule,
        val runCount: Int = 0,
        val maxRuns: Int? = null,
        val enabled: Boolean = true,
    )

    @Serializable
    sealed class Schedule {
        @Serializable
        @SerialName("interval")
        data class Interval(val intervalMillis: Long) : Schedule()

        @Serializable
        @SerialName("cron")
        data class Cron(val expression: String) : Schedule()
    }

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Scheduler",
        description = "Cron / interval scheduler that repeatedly enqueues work for other agents.",
        capabilities = setOf(AgentCapability.BACKGROUND),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse { return AgentResult.failure("Invalid scheduler payload: ${it.message}", it) }

        if (payload.targetAgentId.isBlank()) {
            return AgentResult.failure("targetAgentId is required")
        }

        if (!payload.enabled) {
            context.logger.i("Scheduler disabled — stopping")
            return AgentResult.success("scheduler disabled")
        }

        if (payload.maxRuns != null && payload.runCount >= payload.maxRuns) {
            context.logger.i("Scheduler reached maxRuns=${payload.maxRuns} — stopping")
            return AgentResult.success("maxRuns reached (${payload.maxRuns})")
        }

        val now = System.currentTimeMillis()
        val nextAt = when (val sched = payload.schedule) {
            is Schedule.Interval -> {
                if (sched.intervalMillis < MIN_INTERVAL_MS) {
                    return AgentResult.failure(
                        "intervalMillis must be >= $MIN_INTERVAL_MS (got ${sched.intervalMillis})",
                    )
                }
                now + sched.intervalMillis
            }
            is Schedule.Cron -> {
                CronNext.nextFire(sched.expression, now)
                    ?: return AgentResult.failure("Invalid or unsatisfiable cron: '${sched.expression}'")
            }
        }

        val priority = when (payload.targetPriority.uppercase()) {
            "LOW" -> TaskPriority.LOW
            "HIGH" -> TaskPriority.HIGH
            else -> TaskPriority.NORMAL
        }

        // 1. Enqueue the actual work for the target agent at the computed time.
        val targetId = context.enqueue(
            Task(
                agentId = payload.targetAgentId,
                title = payload.targetTitle,
                payload = payload.targetPayload,
                priority = priority,
                scheduledAt = nextAt,
            ),
        )
        context.logger.i(
            "Scheduled target '${payload.targetAgentId}' ($targetId) for ${formatTs(nextAt)} " +
                "(run #${payload.runCount + 1}${payload.maxRuns?.let { "/$it" } ?: ""})",
        )

        // 2. Re-arm this scheduler so the subsequent occurrence is planned.
        val nextPayload = payload.copy(runCount = payload.runCount + 1)
        context.enqueue(
            Task(
                agentId = ID,
                title = "Scheduler → ${payload.targetAgentId} #${nextPayload.runCount + 1}",
                payload = json.encodeToString(nextPayload),
                priority = TaskPriority.LOW,
                scheduledAt = nextAt,
            ),
        )

        context.memory.put(KEY_LAST_TARGET, payload.targetAgentId)
        context.memory.put(KEY_LAST_FIRE, nextAt.toString())
        context.memory.put(KEY_RUN_COUNT, nextPayload.runCount.toString())

        return AgentResult.success(
            "scheduled ${payload.targetAgentId} at $nextAt (run ${nextPayload.runCount})",
        )
    }

    companion object {
        const val ID = "scheduler"
        const val KEY_LAST_TARGET = "last_target_agent"
        const val KEY_LAST_FIRE = "last_fire_at"
        const val KEY_RUN_COUNT = "run_count"
        private const val MIN_INTERVAL_MS = 5_000L

        private fun formatTs(millis: Long): String =
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toString()
    }
}

/**
 * Minimal 5-field cron next-fire calculator.
 *
 * Fields (space-separated): minute hour day-of-month month day-of-week
 * Supported tokens per field: `*`, `N`, `A-B`, `*/S`, `A,B,C`, combinations.
 * Day-of-week: 0 or 7 = Sunday … 6 = Saturday (Java DayOfWeek compatible).
 *
 * Pure JVM, no third-party dependency. Scans forward minute-by-minute up to
 * one year; returns null on parse failure or no match within the window.
 */
internal object CronNext {

    fun nextFire(expression: String, fromMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val fields = expression.trim().split(Regex("\\s+"))
        if (fields.size != 5) return null

        val minutes = parseField(fields[0], 0, 59) ?: return null
        val hours = parseField(fields[1], 0, 23) ?: return null
        val daysOfMonth = parseField(fields[2], 1, 31) ?: return null
        val months = parseField(fields[3], 1, 12) ?: return null
        val daysOfWeek = parseField(fields[4], 0, 7) ?: return null

        // Normalize 7 → 0 for Sunday so both conventions work.
        val dowSet = daysOfWeek.map { if (it == 7) 0 else it }.toSet()

        var t = Instant.ofEpochMilli(fromMillis)
            .atZone(zone)
            .plusMinutes(1)
            .withSecond(0)
            .withNano(0)
        val limit = t.plusYears(1)

        while (t.isBefore(limit)) {
            val dow = t.dayOfWeek.value % 7 // Java: Mon=1 … Sun=7 → 1..6,0
            if (minutes.contains(t.minute) &&
                hours.contains(t.hour) &&
                daysOfMonth.contains(t.dayOfMonth) &&
                months.contains(t.monthValue) &&
                dowSet.contains(dow)
            ) {
                return t.toInstant().toEpochMilli()
            }
            t = t.plusMinutes(1)
        }
        return null
    }

    /** Parse one cron field into the set of matching integers in [min, max]. */
    fun parseField(field: String, min: Int, max: Int): Set<Int>? {
        if (field.isBlank()) return null
        val result = mutableSetOf<Int>()
        for (part in field.split(',')) {
            val token = part.trim()
            when {
                token == "*" -> {
                    for (i in min..max) result += i
                }
                token.startsWith("*/") -> {
                    val step = token.removePrefix("*/").toIntOrNull() ?: return null
                    if (step <= 0) return null
                    var v = min
                    while (v <= max) {
                        result += v
                        v += step
                    }
                }
                "-" in token -> {
                    val bits = token.split('-')
                    if (bits.size != 2) return null
                    val a = bits[0].toIntOrNull() ?: return null
                    val b = bits[1].toIntOrNull() ?: return null
                    if (a > b || a < min || b > max) return null
                    for (i in a..b) result += i
                }
                else -> {
                    val v = token.toIntOrNull() ?: return null
                    if (v < min || v > max) return null
                    result += v
                }
            }
        }
        return result.ifEmpty { null }
    }
}
