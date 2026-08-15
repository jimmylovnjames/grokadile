package com.grokadile.agent.builtin

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentCapability
import com.grokadile.domain.agent.AgentContext
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentResult
import com.grokadile.domain.agent.AppCatalogProvider
import com.grokadile.domain.model.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * List, find, or launch launcher-visible apps by label or package name.
 *
 * Payload: `{"mode":"launch|list|find","query":"Maps","packageName":"..."}`.
 * Bare non-JSON text is treated as launch-by-query.
 */
@Singleton
class AppLaunchAgent @Inject constructor(
    private val catalog: AppCatalogProvider,
    private val json: Json,
) : Agent {

    @Serializable
    data class Payload(
        val mode: String = MODE_LAUNCH,
        val query: String? = null,
        val packageName: String? = null,
        val limit: Int = 20,
    )

    override val descriptor = AgentDescriptor(
        id = ID,
        name = "App Launch",
        description = "Find and open installed apps by name or package.",
        capabilities = setOf(AgentCapability.DEVICE),
    )

    override suspend fun execute(task: Task, context: AgentContext): AgentResult {
        val payload = runCatching { json.decodeFromString<Payload>(task.payload) }
            .getOrElse {
                if (task.payload.isNotBlank() && !task.payload.trimStart().startsWith("{")) {
                    return launch(Payload(query = task.payload), context)
                }
                return AgentResult.failure("Invalid app_launch payload: ${it.message}", it)
            }

        return when (payload.mode.lowercase()) {
            MODE_LIST -> list(payload, context)
            MODE_FIND -> find(payload, context)
            MODE_LAUNCH, "open", "start" -> launch(payload, context)
            else -> AgentResult.failure("Unknown mode '${payload.mode}'. Use launch|list|find")
        }
    }

    private fun list(payload: Payload, context: AgentContext): AgentResult {
        val apps = catalog.listLaunchable(payload.limit.coerceIn(1, 200))
        context.logger.i("listed ${apps.size} launchable apps")
        val body = apps.joinToString("\n") { "${it.label}  (${it.packageName})" }
        return AgentResult.success(body.ifBlank { "(no launchable apps)" })
    }

    private fun find(payload: Payload, context: AgentContext): AgentResult {
        val query = payload.query?.takeIf { it.isNotBlank() }
            ?: payload.packageName?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("query required for find")
        val hits = catalog.find(query, payload.limit.coerceIn(1, 50))
        context.logger.i("find \"$query\" → ${hits.size}")
        if (hits.isEmpty()) return AgentResult.success("(no apps matching \"$query\")")
        return AgentResult.success(hits.joinToString("\n") { "${it.label}  (${it.packageName})" })
    }

    private suspend fun launch(payload: Payload, context: AgentContext): AgentResult {
        val pkg = payload.packageName?.takeIf { it.isNotBlank() }
        if (pkg != null) {
            val ok = catalog.launch(pkg)
            return if (ok) {
                context.memory.put("last_launched", pkg)
                context.logger.i("launched $pkg")
                AgentResult.success("launched $pkg")
            } else {
                AgentResult.failure("could not launch $pkg")
            }
        }
        val query = payload.query?.takeIf { it.isNotBlank() }
            ?: return AgentResult.failure("query or packageName required for launch")
        val hits = catalog.find(query, limit = 8)
        if (hits.isEmpty()) return AgentResult.failure("no app matching \"$query\"")
        val exact = hits.firstOrNull { it.label.equals(query, ignoreCase = true) }
            ?: hits.firstOrNull { it.packageName.equals(query, ignoreCase = true) }
        val chosen = exact ?: hits.singleOrNull()
        if (chosen == null) {
            val options = hits.joinToString("\n") { "${it.label}  (${it.packageName})" }
            return AgentResult.failure("ambiguous \"$query\":\n$options")
        }
        val ok = catalog.launch(chosen.packageName)
        return if (ok) {
            context.memory.put("last_launched", chosen.packageName)
            context.logger.i("launched ${chosen.label} (${chosen.packageName})")
            AgentResult.success("launched ${chosen.label}")
        } else {
            AgentResult.failure("could not launch ${chosen.label}")
        }
    }

    companion object {
        const val ID = "app_launch"
        const val MODE_LAUNCH = "launch"
        const val MODE_LIST = "list"
        const val MODE_FIND = "find"
    }
}
