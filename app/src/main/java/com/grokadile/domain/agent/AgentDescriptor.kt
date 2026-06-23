package com.grokadile.domain.agent

/** Coarse capabilities an agent declares so the host can gate it on permissions. */
enum class AgentCapability { NETWORK, ACCESSIBILITY, OVERLAY, NOTIFICATIONS, BACKGROUND }

/**
 * Static, declarative metadata about an agent plugin. Surfaced in the UI and
 * used by the registry/orchestrator. Keep this free of runtime state.
 */
data class AgentDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0.0",
    val capabilities: Set<AgentCapability> = emptySet(),
    val enabledByDefault: Boolean = true,
)
