package com.grokadile.ui.screen.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grokadile.domain.model.AgentSettings
import com.grokadile.domain.repository.SettingsRepository
import com.grokadile.permission.PermissionManager
import com.grokadile.permission.PermissionStatus
import com.grokadile.permission.PermissionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val permissionManager: PermissionManager,
) : ViewModel() {

    val settings: StateFlow<AgentSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentSettings())

    private val _permissions = MutableStateFlow(permissionManager.snapshot())
    val permissions: StateFlow<List<PermissionStatus>> = _permissions.asStateFlow()

    fun refreshPermissions() {
        _permissions.value = permissionManager.snapshot()
    }

    fun settingsIntentFor(type: PermissionType): Intent =
        permissionManager.settingsIntentFor(type)

    fun setGrokModel(model: String) {
        viewModelScope.launch { settingsRepository.setGrokModel(model) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { settingsRepository.setGrokApiKey(key) }
    }

    fun setConcurrency(count: Int) {
        viewModelScope.launch { settingsRepository.setMaxConcurrency(count) }
    }

    fun setRunOnBattery(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRunOnBattery(enabled) }
    }
}
