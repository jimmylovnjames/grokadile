package com.grokadile.ui.screen.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grokadile.agent.AgentController
import com.grokadile.agent.builtin.EchoAgent
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentRegistry
import com.grokadile.domain.model.AgentSettings
import com.grokadile.domain.model.Task
import com.grokadile.domain.repository.SettingsRepository
import com.grokadile.domain.repository.TaskCounts
import com.grokadile.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val running: Boolean = false,
    val activeCount: Int = 0,
    val counts: TaskCounts = TaskCounts(),
    val settings: AgentSettings = AgentSettings(),
    val agents: List<AgentDescriptor> = emptyList(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val controller: AgentController,
    settingsRepository: SettingsRepository,
    taskRepository: TaskRepository,
    agentRegistry: AgentRegistry,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        controller.engineState,
        taskRepository.observeCounts(),
        settingsRepository.settings,
        agentRegistry.descriptors,
    ) { engine, counts, settings, agents ->
        DashboardUiState(
            running = engine.running,
            activeCount = engine.activeCount,
            counts = counts,
            settings = settings,
            agents = agents,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun setAutonomous(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) controller.startAutonomous() else controller.stopAutonomous()
        }
    }

    fun runSampleTask() {
        viewModelScope.launch {
            controller.enqueue(
                Task(
                    agentId = EchoAgent.ID,
                    title = "Echo sample",
                    payload = "{\"hello\":\"grokadile\"}",
                ),
            )
        }
    }
}
