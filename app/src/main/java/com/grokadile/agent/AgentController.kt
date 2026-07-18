package com.grokadile.agent

import android.content.Context
import com.grokadile.agent.runtime.EngineState
import com.grokadile.agent.runtime.OrchestrationEngine
import com.grokadile.domain.model.Task
import com.grokadile.domain.repository.SettingsRepository
import com.grokadile.service.AgentForegroundService
import com.grokadile.worker.AgentWorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade the UI layer uses to drive autonomy without touching Android service
 * plumbing directly. Turning autonomy on persists the preference (so boot/
 * heartbeat can restore it), starts the foreground service, and schedules the
 * periodic safety net.
 */
@Singleton
class AgentController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val engine: OrchestrationEngine,
) {
    val engineState: StateFlow<EngineState> = engine.state

    suspend fun startAutonomous() {
        settingsRepository.setAutonomousEnabled(true)
        AgentForegroundService.start(context)
        AgentWorkScheduler.schedule(context)
    }

    suspend fun stopAutonomous() {
        settingsRepository.setAutonomousEnabled(false)
        AgentWorkScheduler.cancel(context)
        AgentForegroundService.stop(context)
    }

    /** Persist a task; it runs as soon as the engine is active. */
    suspend fun enqueue(task: Task): String = engine.enqueue(task)
}
