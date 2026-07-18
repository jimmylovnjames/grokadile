package com.grokadile.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.grokadile.data.remote.auth.AuthTokenStore
import com.grokadile.di.ApplicationScope
import com.grokadile.domain.model.AgentSettings
import com.grokadile.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences-backed settings. NOTE: the Grok API key is stored in DataStore
 * for simplicity; for production move it behind the Android Keystore
 * (EncryptedSharedPreferences). It is excluded from cloud backup via
 * res/xml/backup_rules.xml and never written to logs.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val tokenStore: AuthTokenStore,
    @ApplicationScope scope: CoroutineScope,
) : SettingsRepository {

    private object Keys {
        val AUTONOMOUS = booleanPreferencesKey("autonomous_enabled")
        val MODEL = stringPreferencesKey("grok_model")
        val MAX_CONCURRENCY = intPreferencesKey("max_concurrency")
        val RUN_ON_BATTERY = booleanPreferencesKey("run_on_battery")
        val API_KEY = stringPreferencesKey("grok_api_key")
    }

    init {
        // Prime the in-memory bearer token so networking works from first call.
        scope.launch { tokenStore.set(getGrokApiKey()) }
    }

    override val settings: Flow<AgentSettings> = dataStore.data.map { it.toSettings() }

    override suspend fun current(): AgentSettings = dataStore.data.first().toSettings()

    override suspend fun setAutonomousEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTONOMOUS] = enabled }
    }

    override suspend fun setGrokModel(model: String) {
        dataStore.edit { it[Keys.MODEL] = model }
    }

    override suspend fun setMaxConcurrency(count: Int) {
        dataStore.edit { it[Keys.MAX_CONCURRENCY] = count.coerceIn(1, 8) }
    }

    override suspend fun setRunOnBattery(enabled: Boolean) {
        dataStore.edit { it[Keys.RUN_ON_BATTERY] = enabled }
    }

    override suspend fun setGrokApiKey(key: String) {
        dataStore.edit { it[Keys.API_KEY] = key }
        tokenStore.set(key)
    }

    override suspend fun getGrokApiKey(): String? =
        dataStore.data.first()[Keys.API_KEY]?.takeIf { it.isNotBlank() }

    private fun Preferences.toSettings() = AgentSettings(
        autonomousEnabled = this[Keys.AUTONOMOUS] ?: false,
        grokModel = this[Keys.MODEL] ?: "grok-2-latest",
        maxConcurrency = this[Keys.MAX_CONCURRENCY] ?: 2,
        hasApiKey = !this[Keys.API_KEY].isNullOrBlank(),
        runOnBattery = this[Keys.RUN_ON_BATTERY] ?: true,
    )
}
