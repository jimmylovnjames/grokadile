package com.grokadile.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.grokadile.agent.runtime.OrchestrationEngine
import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.repository.TaskRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The persistent host for autonomous agents. While alive it keeps the
 * [OrchestrationEngine] dispatch loop running and reflects live state in an
 * ongoing notification. START_STICKY + the boot receiver + the WorkManager
 * heartbeat together make the orchestrator resilient to process death.
 */
@AndroidEntryPoint
class AgentForegroundService : LifecycleService() {

    @Inject lateinit var engine: OrchestrationEngine
    @Inject lateinit var taskRepository: TaskRepository
    @Inject lateinit var notifications: AgentNotifications
    @Inject lateinit var logger: GrokLogger

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannel()
        startAsForeground()
        acquireWakeLock()
        engine.start(lifecycleScope)
        observeStatus()
        logger.i(TAG, "Foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            logger.i(TAG, "Stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        // Re-deliver so the OS recreates us if the process is killed.
        return START_STICKY
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            combine(engine.state, taskRepository.observeCounts()) { state, counts ->
                state.activeCount to counts.pending
            }.collect { (running, queued) ->
                notifications.update(running, queued)
            }
        }
    }

    private fun startAsForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            AgentNotifications.NOTIFICATION_ID,
            notifications.build(running = 0, queued = 0),
            type,
        )
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
            // Safety timeout so a stuck service can never pin the CPU forever.
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        engine.stop()
        releaseWakeLock()
        logger.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.grokadile.action.STOP_AGENTS"
        private const val TAG = "AgentService"
        private const val WAKELOCK_TAG = "grokadile:agents"
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1h safety cap

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AgentForegroundService::class.java)
                    .setAction(ACTION_STOP),
            )
        }
    }
}
