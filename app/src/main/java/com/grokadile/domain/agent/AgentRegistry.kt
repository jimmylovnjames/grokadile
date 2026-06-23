package com.grokadile.domain.agent

import kotlinx.coroutines.flow.StateFlow

/**
 * Runtime catalog of available agent plugins. Built-in agents are contributed
 * at startup via Hilt multibinding; additional agents can be registered
 * dynamically (e.g. a future remote/plugin loader).
 */
interface AgentRegistry {
    /** Observable set of descriptors for UI (enable/disable, capabilities). */
    val descriptors: StateFlow<List<AgentDescriptor>>

    fun register(agent: Agent)
    fun unregister(agentId: String)
    fun get(agentId: String): Agent?
    fun all(): List<Agent>
    fun isRegistered(agentId: String): Boolean
}
