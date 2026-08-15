package com.grokadile.ui.screen.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grokadile.agent.AgentController
import com.grokadile.agent.builtin.DeviceHealthAgent
import com.grokadile.agent.builtin.EchoAgent
import com.grokadile.agent.builtin.PlannerAgent
import com.grokadile.domain.agent.AgentDescriptor
import com.grokadile.domain.agent.AgentRegistry
import com.grokadile.domain.agent.DeviceHealth
import com.grokadile.domain.agent.DeviceHealthProvider
import com.grokadile.domain.model.AgentSettings
import com.grokadile.domain.model.Task
import com.grokadile.domain.model.VectorSearchHit
import com.grokadile.domain.repository.SettingsRepository
import com.grokadile.domain.repository.TaskCounts
import com.grokadile.domain.repository.TaskRepository
import com.grokadile.domain.repository.VectorMemoryRepository
import com.grokadile.service.VoiceAssistantService
import com.grokadile.voice.ChatMessageUi
import com.grokadile.voice.VoiceAssistant
import com.grokadile.voice.VoiceUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

data class DashboardExtras(
    val memoryCount: Int = 0,
    val health: DeviceHealth? = null,
    val memoryQuery: String = "",
    val memoryHits: List<VectorSearchHit> = emptyList(),
)

data class DashboardUiState(
    val running: Boolean = false,
    val activeCount: Int = 0,
    val counts: TaskCounts = TaskCounts(),
    val settings: AgentSettings = AgentSettings(),
    val agents: List<AgentDescriptor> = emptyList(),
    val chatDraft: String = "",
    val extras: DashboardExtras = DashboardExtras(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: AgentController,
    private val settingsRepository: SettingsRepository,
    private val voiceAssistant: VoiceAssistant,
    private val memoryStore: VectorMemoryRepository,
    private val deviceHealth: DeviceHealthProvider,
    taskRepository: TaskRepository,
    agentRegistry: AgentRegistry,
) : ViewModel() {

    private val chatDraft = MutableStateFlow("")
    private val extras = MutableStateFlow(DashboardExtras())

    val messages: StateFlow<List<ChatMessageUi>> = voiceAssistant.messages
    val voiceState: StateFlow<VoiceUiState> = voiceAssistant.state

    val uiState: StateFlow<DashboardUiState> = combine(
        combine(
            controller.engineState,
            taskRepository.observeCounts(),
            settingsRepository.settings,
            agentRegistry.descriptors,
            chatDraft,
        ) { engine, counts, settings, agents, draft ->
            DashboardUiState(
                running = engine.running,
                activeCount = engine.activeCount,
                counts = counts,
                settings = settings,
                agents = agents,
                chatDraft = draft,
            )
        },
        extras,
    ) { base, extra -> base.copy(extras = extra) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        viewModelScope.launch {
            val settings = settingsRepository.current()
            if (settings.voiceListeningEnabled && !voiceAssistant.state.value.enabled) {
                VoiceAssistantService.start(context)
            }
        }
        viewModelScope.launch {
            while (true) {
                refreshExtras()
                delay(10_000)
            }
        }
    }

    private suspend fun refreshExtras() {
        val count = runCatching { memoryStore.count() }.getOrDefault(0)
        val health = runCatching { deviceHealth.snapshot() }.getOrNull()
        extras.value = extras.value.copy(memoryCount = count, health = health)
    }

    fun setAutonomous(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) controller.startAutonomous() else controller.stopAutonomous()
        }
    }

    fun setVoiceListening(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setVoiceListeningEnabled(enabled)
            if (enabled) {
                VoiceAssistantService.start(context)
            } else {
                voiceAssistant.setListeningEnabled(false)
                VoiceAssistantService.stop(context)
            }
        }
    }

    fun setChatDraft(value: String) {
        chatDraft.value = value
    }

    fun sendChat() {
        val text = chatDraft.value.trim()
        if (text.isBlank()) return
        chatDraft.value = ""
        voiceAssistant.submitText(text)
    }

    fun startPushToTalk() {
        voiceAssistant.startPushToTalk()
    }

    fun stopPushToTalk() {
        voiceAssistant.stopPushToTalk()
    }

    fun setMemoryQuery(value: String) {
        extras.value = extras.value.copy(memoryQuery = value)
    }

    fun searchMemory() {
        val query = extras.value.memoryQuery.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            val hits = runCatching { memoryStore.search(query, limit = 5) }.getOrDefault(emptyList())
            extras.value = extras.value.copy(memoryHits = hits)
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

    fun runHealthCheck() {
        viewModelScope.launch {
            controller.enqueue(
                Task(
                    agentId = DeviceHealthAgent.ID,
                    title = "Health check",
                    payload = buildJsonObject { put("mode", DeviceHealthAgent.MODE_STATUS) }.toString(),
                ),
            )
            if (!controller.engineState.value.running) controller.startAutonomous()
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            controller.enqueue(
                Task(
                    agentId = DeviceHealthAgent.ID,
                    title = "Retry failed tasks",
                    payload = buildJsonObject { put("mode", DeviceHealthAgent.MODE_RETRY) }.toString(),
                ),
            )
            if (!controller.engineState.value.running) controller.startAutonomous()
        }
    }

    fun runSamplePlan() {
        viewModelScope.launch {
            controller.enqueue(
                Task(
                    agentId = PlannerAgent.ID,
                    title = "Sample plan",
                    payload = buildJsonObject {
                        put("goal", "Check device health, then remember a one-line status note")
                        put("dryRun", false)
                    }.toString(),
                ),
            )
            if (!controller.engineState.value.running) controller.startAutonomous()
        }
    }
}
