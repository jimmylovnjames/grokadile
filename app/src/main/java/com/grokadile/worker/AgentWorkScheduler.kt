package com.grokadile.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Registers/cancels the periodic heartbeat work. */
object AgentWorkScheduler {

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AgentHeartbeatWorker>(
            15, TimeUnit.MINUTES,
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            AgentHeartbeatWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(AgentHeartbeatWorker.NAME)
    }
}
