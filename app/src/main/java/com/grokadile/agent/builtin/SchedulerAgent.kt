package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Autonomous scheduler: fires a target agent on a recurring schedule and
 * re-arms itself for the next trigger. Supports two schedule kinds:
 *
 * - **interval** — every N milliseconds (`intervalMillis`).
 * - **cron** — classic 5-field expression (`minute hour day-of-month month day-of-week`).
 *
 * Expected payload (JSON):
 * ```
 * {
 *   "scheduleId": "morning-brief",          // optional stable key for memory
 *   "type": "cron",                         // "cron" | "interval"
 *   "expression": "0 9 * * *",              // required for cron
 *   "intervalMillis": 3600000,              // required for interval
 *   "targetAgentId": "grok.chat",
 *   "targetTitle": "Daily brief",
 *   "targetPayload": "{\"prompt\":\"…\"}",
 *   "targetPriority": "NORMAL",             // LOW | NORMAL | HIGH
 *   "maxFires": null,                       // optional hard stop
 *   "fireCount": 0                          // internal counter, do not set
 * }
 * ```
 *
 * Cron fields: minute (0-59), hour (0-23), day-of-month (1-31), month (1-12),
 * day-of-week (0-6, Sun=0). Supports `*`, single numbers, lists (`1,15`),
 * ranges (`1-5`), and steps (star/15, `9-17/2`).
 *
 * Timezone is the device default. The agent enqueues the *target* task for
 * immediate execution, then schedules its own next wake via [Task.scheduledAt].
 */
@Singleton
class SchedulerAgent @Inject constructor(
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val scheduleId: String? = null,
        val type: String = TYPE_INTERVAL,
        val expression: String? = null,
        val intervalMillis: Long? = null,
        val targetAgentId: String,
        val targetTitle: String = "Scheduled task",
        val targetPayload: String = "{}",
        val targetPriority: String = "NORMAL",
        val maxFires: Int? = null,
        val fireCount: Int = 0,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Scheduler",
        description = "Fires another agent on a cron or interval schedule and re-arms itself.",
        capabilities = setOf(AgentCapability.BACKGROUND),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse { return AgentResult.failure("Invalid scheduler payload: ${it.message}", it) }

        if (payload.targetAgentId.isBlank()) {
            return AgentResult.failure("targetAgentId is required")
        }

        val scheduleKey = payload.scheduleId?.takeIf { it.isNotBlank() }
            ?: "sched-${payload.targetAgentId}-${payload.type}"

        // Optional hard stop.
        val nextCount = payload.fireCount + 1
        if (payload.maxFires != null && nextCount > payload.maxFires) {
            context.logger.i("Schedule '$scheduleKey' reached maxFires=${payload.maxFires}; stopping")
            context.memory.put(memoryKey(scheduleKey), "stopped at fire #$nextCount")
            return AgentResult.success("stopped after ${payload.fireCount} fires")
        }

        // Validate the schedule before enqueueing anything.
        val now = System.currentTimeMillis()
        val nextAt = when (payload.type.lowercase()) {
            TYPE_INTERVAL -> {
                val interval = payload.intervalMillis
                    ?: return AgentResult.failure("intervalMillis required for type=interval")
                if (interval < MIN_INTERVAL_MS) {
                    return AgentResult.failure("intervalMillis must be >= $MIN_INTERVAL_MS")
                }
                now + interval
            }
            TYPE_CRON -> {
                val expr = payload.expression?.trim()
                    ?: return AgentResult.failure("expression required for type=cron")
                val next = runCatching { CronNext.compute(expr, now + 1_000L) }
                    .getOrElse { return AgentResult.failure("Bad cron expression '$expr': ${it.message}", it) }
                next ?: return AgentResult.failure("No matching time for cron '$expr' within search window")
            }
            else -> return AgentResult.failure("Unknown schedule type '${payload.type}' (use interval|cron)")
        }

        // 1) Fire the target agent now.
        val priority = parsePriority(payload.targetPriority)
        val targetTask = Task(
            agentId = payload.targetAgentId,
            title = payload.targetTitle,
            payload = payload.targetPayload,
            priority = priority,
            scheduledAt = now,
        )
        val targetId = context.enqueue(targetTask)
        context.logger.i(
            "Schedule '$scheduleKey' fire #$nextCount → ${payload.targetAgentId} " +
                "(task=$targetId, title='${payload.targetTitle}')",
        )

        // 3) Re-arm this scheduler for the next fire.
        val nextPayload = payload.copy(fireCount = nextCount)
        context.enqueue(
            Task(
                agentId = ID,
                title = "Schedule '$scheduleKey' #${nextCount + 1}",
                payload = json.encodeToString(nextPayload),
                priority = TaskPriority.LOW,
                scheduledAt = nextAt,
            ),
        )

        context.memory.put(
            memoryKey(scheduleKey),
            json.encodeToString(
                mapOf(
                    "lastFireAt" to now.toString(),
                    "nextAt" to nextAt.toString(),
                    "fireCount" to nextCount.toString(),
                    "targetAgentId" to payload.targetAgentId,
                ),
            ),
        )

        val delaySec = (nextAt - now) / 1000
        context.logger.i("Schedule '$scheduleKey' next fire in ${delaySec}s (at $nextAt)")
        return AgentResult.success(
            "fired #$nextCount → ${payload.targetAgentId}; next in ${delaySec}s",
        )
    }

    private fun parsePriority(raw: String): TaskPriority =
        runCatching { TaskPriority.valueOf(raw.uppercase()) }.getOrDefault(TaskPriority.NORMAL)

    private fun memoryKey(scheduleId: String) = "schedule:$scheduleId"

    companion object {
        const val ID = "scheduler"
        const val TYPE_INTERVAL = "interval"
        const val TYPE_CRON = "cron"
        const val MIN_INTERVAL_MS = 15_000L
    }
}

/**
 * Minimal 5-field cron next-time calculator (device default timezone).
 *
 * Field order: minute hour day-of-month month day-of-week
 * - day-of-week: 0=Sunday … 6=Saturday (also accepts 7=Sunday)
 * - Supports: `*`, `n`, `a,b,c`, `a-b`, star/n, `a-b/n`
 */
object CronNext {

    private const val MAX_SEARCH_MS = 366L * 24 * 60 * 60 * 1000 // ~1 year

    fun compute(expression: String, afterMillis: Long, tz: TimeZone = TimeZone.getDefault()): Long? {
        val fields = expression.trim().split(Regex("\\s+"))
        require(fields.size == 5) { "expected 5 fields, got ${fields.size}" }

        val minute = Field.parse(fields[0], 0, 59)
        val hour = Field.parse(fields[1], 0, 23)
        val dom = Field.parse(fields[2], 1, 31)
        val month = Field.parse(fields[3], 1, 12)
        val dow = Field.parse(fields[4], 0, 7) // 7 → treat as Sunday (0)

        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = afterMillis
        // Align to the start of the next whole minute.
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= afterMillis) {
            cal.add(Calendar.MINUTE, 1)
        }

        val deadline = afterMillis + MAX_SEARCH_MS
        while (cal.timeInMillis <= deadline) {
            val m = cal.get(Calendar.MINUTE)
            val h = cal.get(Calendar.HOUR_OF_DAY)
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val mon = cal.get(Calendar.MONTH) + 1 // Calendar is 0-based
            val w = cal.get(Calendar.DAY_OF_WEEK) // Calendar: 1=Sun … 7=Sat
            val w0 = w - 1 // convert to 0=Sun … 6=Sat

            val dowMatch = dow.matches(w0) || (w0 == 0 && dow.matches(7))
            if (minute.matches(m) && hour.matches(h) && dom.matches(d) &&
                month.matches(mon) && dowMatch
            ) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    /** One cron field matcher. */
    class Field private constructor(private val allowed: BooleanArray) {
        fun matches(value: Int): Boolean =
            value in allowed.indices && allowed[value]

        companion object {
            fun parse(token: String, min: Int, max: Int): Field {
                val allowed = BooleanArray(max + 1)
                for (part in token.split(',')) {
                    val stepSplit = part.split('/')
                    val rangePart = stepSplit[0]
                    val step = if (stepSplit.size > 1) stepSplit[1].toInt() else 1
                    require(step >= 1) { "step must be >= 1 in '$part'" }

                    val (lo, hi) = when {
                        rangePart == "*" -> min to max
                        rangePart.contains('-') -> {
                            val (a, b) = rangePart.split('-', limit = 2)
                            a.toInt() to b.toInt()
                        }
                        else -> {
                            val v = rangePart.toInt()
                            v to v
                        }
                    }
                    require(lo in min..max && hi in min..max && lo <= hi) {
                        "out of range $lo-$hi for field bounds $min-$max"
                    }
                    var v = lo
                    while (v <= hi) {
                        allowed[v] = true
                        v += step
                    }
                }
                return Field(allowed)
            }
        }
    }
}
