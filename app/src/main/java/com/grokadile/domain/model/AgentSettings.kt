package com.grokadile.domain.model

/** User-tunable runtime configuration for the orchestrator and agents. */
data class AgentSettings(
    val autonomousEnabled: Boolean = false,
    val grokModel: String = "grok-2-latest",
    val maxConcurrency: Int = 2,
    val hasApiKey: Boolean = false,
    /** When false the orchestrator processes only manually-triggered tasks. */
    val runOnBattery: Boolean = true,
)
