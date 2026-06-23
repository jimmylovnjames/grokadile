package com.grokadile.agent.runtime

import com.grokadile.domain.agent.Agent
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe registry seeded from Hilt multibinding (all built-in agents) and
 * open to dynamic [register]/[unregister] for a future remote plugin loader.
 */
@Singleton
class DefaultAgentRegistry @Inject constructor(
    boundAgents: Set<@JvmSuppressWildcards Agent>,
) : AgentRegistry {

    private val agents = ConcurrentHashMap<String, Agent>()
    private val _descriptors = MutableStateFlow<List<AgentDescriptor>>(emptyList())
    override val descriptors: StateFlow<List<AgentDescriptor>> = _descriptors.asStateFlow()

    init {
        boundAgents.forEach { agents[it.id] = it }
        refresh()
    }

    override fun register(agent: Agent) {
        agents[agent.id] = agent
        refresh()
    }

    override fun unregister(agentId: String) {
        agents.remove(agentId)
        refresh()
    }

    override fun get(agentId: String): Agent? = agents[agentId]
    override fun all(): List<Agent> = agents.values.toList()
    override fun isRegistered(agentId: String): Boolean = agents.containsKey(agentId)

    private fun refresh() {
        _descriptors.value = agents.values.map { it.descriptor }.sortedBy { it.name }
    }
}
