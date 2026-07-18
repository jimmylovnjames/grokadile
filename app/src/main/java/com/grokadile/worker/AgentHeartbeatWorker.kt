package com.grokadile.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.repository.LogRepository
import com.grokadile.domain.repository.SettingsRepository
import com.grokadile.service.AgentForegroundService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic safety net (min 15-minute cadence). If autonomy is enabled it makes
 * sure the foreground service is alive (re-arming it after a kill the boot
 * receiver didn't catch), and it caps log-table growth.
 */
@HiltWorker
class AgentHeartbeatWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val logRepository: LogRepository,
    private val logger: GrokLogger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        logRepository.trimTo(MAX_LOG_ROWS)
        if (settingsRepository.current().autonomousEnabled) {
            AgentForegroundService.start(applicationContext)
        }
        Result.success()
    } catch (e: Exception) {
        logger.w(TAG, "Heartbeat failed: ${e.message}", e)
        Result.retry()
    }

    companion object {
        const val NAME = "agent-heartbeat"
        private const val TAG = "Heartbeat"
        private const val MAX_LOG_ROWS = 5_000
    }
}
