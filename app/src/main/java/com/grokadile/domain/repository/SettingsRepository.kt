package com.grokadile.domain.repository

import com.grokadile.domain.model.AgentSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AgentSettings>
    suspend fun current(): AgentSettings

    suspend fun setAutonomousEnabled(enabled: Boolean)
    suspend fun setGrokModel(model: String)
    suspend fun setMaxConcurrency(count: Int)
    suspend fun setRunOnBattery(enabled: Boolean)

    /** Stored separately from observable settings; never logged or backed up. */
    suspend fun setGrokApiKey(key: String)
    suspend fun getGrokApiKey(): String?
}
