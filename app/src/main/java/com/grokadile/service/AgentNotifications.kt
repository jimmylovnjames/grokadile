package com.grokadile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.grokadile.MainActivity
import com.grokadile.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the agent notification channel and renders the ongoing status notification. */
@Singleton
class AgentNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.agent_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.agent_channel_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun build(running: Int, queued: Int): Notification {
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, AgentForegroundService::class.java)
                .setAction(AgentForegroundService.ACTION_STOP),
            pendingFlags,
        )

        val text = if (running == 0 && queued == 0) {
            context.getString(R.string.agent_notification_idle)
        } else {
            context.getString(R.string.agent_notification_text, running, queued)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(context.getString(R.string.agent_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.action_stop_all), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun update(running: Int, queued: Int) {
        manager.notify(NOTIFICATION_ID, build(running, queued))
    }

    companion object {
        const val CHANNEL_ID = "agents"
        const val NOTIFICATION_ID = 1001
    }
}
