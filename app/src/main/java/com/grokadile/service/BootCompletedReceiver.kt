package com.grokadile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.grokadile.domain.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Restarts the orchestrator after reboot iff the user left autonomy enabled. */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val appContext = context.applicationContext
        val pending = goAsync()
        // Short-lived scope: a reboot check is a one-shot suspend read.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                if (settingsRepository.current().autonomousEnabled) {
                    AgentForegroundService.start(appContext)
                }
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
