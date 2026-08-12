package com.grokadile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.grokadile.MainActivity
import com.grokadile.R
import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.repository.SettingsRepository
import com.grokadile.voice.VoiceAssistant
import com.grokadile.voice.VoicePhase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Microphone foreground service that keeps Siri-style wake-word listening alive
 * while the user has voice activation enabled (including when the UI is backgrounded).
 */
@AndroidEntryPoint
class VoiceAssistantService : LifecycleService() {

    @Inject lateinit var voiceAssistant: VoiceAssistant
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var logger: GrokLogger

    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var stoppingIntentionally = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startAsForeground(getString(R.string.voice_notification_title))
        voiceAssistant.setListeningEnabled(true)
        observePhase()
        logger.i(TAG, "Voice assistant service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stoppingIntentionally = true
                lifecycleScope.launch {
                    settingsRepository.setVoiceListeningEnabled(false)
                    voiceAssistant.setListeningEnabled(false)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (stoppingIntentionally) {
            voiceAssistant.setListeningEnabled(false)
        }
        super.onDestroy()
        logger.i(TAG, "Voice assistant service destroyed")
    }

    private fun observePhase() {
        lifecycleScope.launch {
            voiceAssistant.state.collectLatest { state ->
                val text = when (state.phase) {
                    VoicePhase.LISTENING_WAKE -> "Listening for Hey Grok…"
                    VoicePhase.LISTENING_COMMAND -> "Listening for a command…"
                    VoicePhase.PROCESSING -> "Working on it…"
                    VoicePhase.SPEAKING -> "Speaking…"
                    VoicePhase.IDLE -> "Voice standby"
                }
                manager.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun startAsForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            type,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.voice_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            pendingFlags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.action_stop_voice), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "VoiceAssistantService"
        const val CHANNEL_ID = "voice"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.grokadile.voice.STOP"

        fun start(context: Context) {
            val intent = Intent(context, VoiceAssistantService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceAssistantService::class.java))
        }
    }
}
