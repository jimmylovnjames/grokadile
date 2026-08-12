package com.grokadile.ui.screen.dashboard

import android.content.Context
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
import com.grokadile.service.VoiceAssistantService
import com.grokadile.voice.ChatMessageUi
import com.grokadile.voice.VoiceAssistant
import com.grokadile.voice.VoiceUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
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
    val chatDraft: String = "",
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: AgentController,
    private val settingsRepository: SettingsRepository,
    private val voiceAssistant: VoiceAssistant,
    taskRepository: TaskRepository,
    agentRegistry: AgentRegistry,
) : ViewModel() {

    private val chatDraft = MutableStateFlow("")

    val messages: StateFlow<List<ChatMessageUi>> = voiceAssistant.messages
    val voiceState: StateFlow<VoiceUiState> = voiceAssistant.state

    val uiState: StateFlow<DashboardUiState> = combine(
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        // Re-arm listening if the preference survived process death but the FGS did not.
        viewModelScope.launch {
            val settings = settingsRepository.current()
            if (settings.voiceListeningEnabled && !voiceAssistant.state.value.enabled) {
                VoiceAssistantService.start(context)
            }
        }
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
