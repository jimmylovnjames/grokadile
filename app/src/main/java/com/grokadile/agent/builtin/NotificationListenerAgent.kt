package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.NotificationContentProvider
import com.grokadile.domain.agent.NotificationSnapshot
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.TaskPriority
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes the notification shade and optionally fires other agents when
 * notifications match user-defined rules. This is the reactive trigger layer.
 *
 * Requires Notification access (Settings → Notification access → Grokadile).
 *
 * Modes: list | match | register_rule | list_rules | clear_rules | remove_rule | poll_and_react
 *
 * Pair poll_and_react with SchedulerAgent for near-real-time reactions.
 */
@Singleton
class NotificationListenerAgent @Inject constructor(
    private val notifications: NotificationContentProvider,
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val mode: String = MODE_LIST,
        val limit: Int = 20,
        val activeOnly: Boolean = true,
        val packageFilter: String? = null,
        val titleContains: String? = null,
        val textContains: String? = null,
        val store: Boolean = true,
        val ruleId: String? = null,
        val targetAgentId: String? = null,
        val targetTitle: String = "Notification reaction",
        val targetPayload: String = "{}",
        val targetPriority: String = "NORMAL",
    )

    @Serializable
    data class ReactionRule(
        val ruleId: String,
        val packageFilter: String? = null,
        val titleContains: String? = null,
        val textContains: String? = null,
        val targetAgentId: String,
        val targetTitle: String = "Notification reaction",
        val targetPayload: String = "{}",
        val targetPriority: String = "NORMAL",
        val lastFiredKey: String? = null,
    )

    @Serializable
    private data class RuleBook(val rules: List<ReactionRule> = emptyList())

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "Notification Listener",
        description = "Reads active/recent notifications and fires agents on matching rules.",
        capabilities = setOf(AgentCapability.NOTIFICATIONS, AgentCapability.BACKGROUND),
        enabledByDefault = true,
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse { return AgentResult.failure("Invalid payload: ${it.message}", it) }

        return when (payload.mode.lowercase()) {
            MODE_LIST, MODE_MATCH -> listOrMatch(payload, context)
            MODE_REGISTER_RULE -> registerRule(payload, context)
            MODE_LIST_RULES -> listRules(context)
            MODE_CLEAR_RULES -> clearRules(context)
            MODE_REMOVE_RULE -> removeRule(payload, context)
            MODE_POLL_AND_REACT -> pollAndReact(payload, context)
            else -> AgentResult.failure(
                "Unknown mode '${payload.mode}'. Use list|match|register_rule|list_rules|clear_rules|remove_rule|poll_and_react",
            )
        }
    }

    private suspend fun listOrMatch(payload: Payload, context: AgentContext): AgentResult {
        if (!notifications.isAvailable()) {
            context.logger.w("Notification listener not connected")
            return AgentResult.retry(
                reason = "Notification access not granted. Enable Grokadile in Settings → Notification access.",
                backoffMillis = 20_000L,
            )
        }

        val hits = if (
            payload.mode.equals(MODE_MATCH, ignoreCase = true) ||
            !payload.packageFilter.isNullOrBlank() ||
            !payload.titleContains.isNullOrBlank() ||
            !payload.textContains.isNullOrBlank()
        ) {
            notifications.findMatching(
                packageFilter = payload.packageFilter,
                titleContains = payload.titleContains,
                textContains = payload.textContains,
                activeOnly = payload.activeOnly,
                limit = payload.limit.coerceIn(1, 100),
            )
        } else if (payload.activeOnly) {
            notifications.activeNotifications(payload.limit.coerceIn(1, 100))
        } else {
            notifications.recentNotifications(payload.limit.coerceIn(1, 100))
        }

        val body = if (hits.isEmpty()) {
            "(no matching notifications)"
        } else {
            hits.joinToString("\n") { it.toCompactLine() }
        }

        if (payload.store) {
            context.memory.put(KEY_LAST_DUMP, body)
            context.memory.put(KEY_LAST_COUNT, hits.size.toString())
            context.memory.put(KEY_LAST_TS, System.currentTimeMillis().toString())
        }

        context.logger.i("Notifications: ${hits.size} matched")
        return AgentResult.success(body)
    }

    private suspend fun registerRule(payload: Payload, context: AgentContext): AgentResult {
        val ruleId = payload.ruleId?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("ruleId is required for register_rule")
        val target = payload.targetAgentId?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("targetAgentId is required for register_rule")

        val rule = ReactionRule(
            ruleId = ruleId,
            packageFilter = payload.packageFilter,
            titleContains = payload.titleContains,
            textContains = payload.textContains,
            targetAgentId = target,
            targetTitle = payload.targetTitle,
            targetPayload = payload.targetPayload,
            targetPriority = payload.targetPriority,
        )

        val book = loadRules(context).toMutableList()
        book.removeAll { it.ruleId == ruleId }
        book.add(rule)
        saveRules(context, book)

        context.logger.i("Registered rule '$ruleId' → $target")
        return AgentResult.success("registered rule '$ruleId' → $target (${book.size} total)")
    }

    private suspend fun listRules(context: AgentContext): AgentResult {
        val book = loadRules(context)
        if (book.isEmpty()) return AgentResult.success("(no reaction rules)")
        val body = book.joinToString("\n") { r ->
            buildString {
                append(r.ruleId)
                append(" → ")
                append(r.targetAgentId)
                r.packageFilter?.let { append(" pkg=$it") }
                r.titleContains?.let { append(" title~$it") }
                r.textContains?.let { append(" text~$it") }
            }
        }
        return AgentResult.success(body)
    }

    private suspend fun clearRules(context: AgentContext): AgentResult {
        saveRules(context, emptyList())
        context.logger.i("Cleared all notification reaction rules")
        return AgentResult.success("cleared all rules")
    }

    private suspend fun removeRule(payload: Payload, context: AgentContext): AgentResult {
        val ruleId = payload.ruleId?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("ruleId is required for remove_rule")
        val book = loadRules(context).filterNot { it.ruleId == ruleId }
        saveRules(context, book)
        return AgentResult.success("removed rule '$ruleId' (${book.size} remaining)")
    }

    private suspend fun pollAndReact(payload: Payload, context: AgentContext): AgentResult {
        if (!notifications.isAvailable()) {
            return AgentResult.retry(
                reason = "Notification access not granted.",
                backoffMillis = 20_000L,
            )
        }

        val book = loadRules(context).toMutableList()
        if (book.isEmpty()) {
            return AgentResult.success("no rules configured — nothing to react to")
        }

        val source = if (payload.activeOnly) {
            notifications.activeNotifications(100)
        } else {
            notifications.recentNotifications(100)
        }

        var fired = 0
        val updated = book.map { rule ->
            val match = source.firstOrNull { snap ->
                snap.matches(rule.packageFilter, rule.titleContains, rule.textContains) &&
                    snap.key != rule.lastFiredKey
            }
            if (match != null) {
                val priority = runCatching {
                    TaskPriority.valueOf(rule.targetPriority.uppercase())
                }.getOrDefault(TaskPriority.NORMAL)

                val enrichedPayload = enrichPayload(rule.targetPayload, match)

                context.enqueue(
                    Task(
                        agentId = rule.targetAgentId,
                        title = rule.targetTitle,
                        payload = enrichedPayload,
                        priority = priority,
                        scheduledAt = System.currentTimeMillis(),
                    ),
                )
                context.logger.i(
                    "Rule '${rule.ruleId}' fired → ${rule.targetAgentId} on ${match.packageName}",
                )
                fired++
                rule.copy(lastFiredKey = match.key)
            } else {
                rule
            }
        }

        saveRules(context, updated)
        val summary = "poll_and_react: $fired rule(s) fired against ${source.size} notifications"
        context.logger.i(summary)
        if (payload.store) {
            context.memory.put(KEY_LAST_REACT, summary)
            context.memory.put(KEY_LAST_TS, System.currentTimeMillis().toString())
        }
        return AgentResult.success(summary)
    }

    private fun enrichPayload(base: String, snap: NotificationSnapshot): String {
        val trimmed = base.trim()
        if (!trimmed.startsWith("{")) return base
        return runCatching {
            val map = json.decodeFromString<Map<String, String>>(trimmed).toMutableMap()
            map.putIfAbsent("notificationPackage", snap.packageName)
            map.putIfAbsent("notificationTitle", snap.title)
            map.putIfAbsent("notificationText", snap.text)
            map.putIfAbsent(
                "prompt",
                map["prompt"] ?: "Notification from ${snap.packageName}: ${snap.title} — ${snap.text}",
            )
            json.encodeToString(map)
        }.getOrDefault(base)
    }

    private suspend fun loadRules(context: AgentContext): List<ReactionRule> {
        val raw = context.memory.get(KEY_RULES) ?: return emptyList()
        return runCatching { json.decodeFromString<RuleBook>(raw).rules }
            .getOrDefault(emptyList())
    }

    private suspend fun saveRules(context: AgentContext, rules: List<ReactionRule>) {
        context.memory.put(KEY_RULES, json.encodeToString(RuleBook(rules)))
    }

    companion object {
        const val ID = "notification_listener"
        const val MODE_LIST = "list"
        const val MODE_MATCH = "match"
        const val MODE_REGISTER_RULE = "register_rule"
        const val MODE_LIST_RULES = "list_rules"
        const val MODE_CLEAR_RULES = "clear_rules"
        const val MODE_REMOVE_RULE = "remove_rule"
        const val MODE_POLL_AND_REACT = "poll_and_react"

        const val KEY_RULES = "notif_rules"
        const val KEY_LAST_DUMP = "last_notif_dump"
        const val KEY_LAST_COUNT = "last_notif_count"
        const val KEY_LAST_REACT = "last_notif_react"
        const val KEY_LAST_TS = "last_notif_ts"
    }
}
