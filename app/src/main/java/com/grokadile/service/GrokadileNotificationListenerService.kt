package com.grokadile.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import com.grokadile.core.logging.GrokLogger
import com.grokadile.domain.agent.NotificationContentProvider
import com.grokadile.domain.agent.NotificationSnapshot
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live notification surface for reactive agents.
 *
 * - Keeps active notifications + a ring buffer of recent posts/removals.
 * - Implements [NotificationContentProvider] so agents stay free of framework types.
 * - Requires the user to grant Notification access in system settings.
 */
@AndroidEntryPoint
class GrokadileNotificationListenerService : NotificationListenerService(),
    NotificationContentProvider {

    @Inject lateinit var logger: GrokLogger

    private val recent = ConcurrentLinkedDeque<NotificationSnapshot>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        logger.i(TAG, "Notification listener connected — reactive triggers live")
        runCatching {
            activeNotifications?.forEach { sbn ->
                pushRecent(toSnapshot(sbn))
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        logger.w(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val snap = toSnapshot(sbn)
        pushRecent(snap)
        logger.d(TAG, "posted: ${snap.packageName} | ${snap.title}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        logger.d(TAG, "removed: ${sbn.packageName}")
    }

    override fun isAvailable(): Boolean = instance != null

    override fun activeNotifications(limit: Int): List<NotificationSnapshot> {
        val list = runCatching {
            activeNotifications?.map { toSnapshot(it) } ?: emptyList()
        }.getOrDefault(emptyList())
        return list
            .sortedByDescending { it.postTime }
            .take(limit.coerceIn(1, 200))
    }

    override fun recentNotifications(limit: Int): List<NotificationSnapshot> =
        recent.toList()
            .sortedByDescending { it.postTime }
            .take(limit.coerceIn(1, 200))

    override fun findMatching(
        packageFilter: String?,
        titleContains: String?,
        textContains: String?,
        activeOnly: Boolean,
        limit: Int,
    ): List<NotificationSnapshot> {
        val source = if (activeOnly) activeNotifications(200) else recentNotifications(200)
        return source
            .filter { it.matches(packageFilter, titleContains, textContains) }
            .take(limit.coerceIn(1, 200))
    }

    private fun pushRecent(snap: NotificationSnapshot) {
        recent.addFirst(snap)
        while (recent.size > MAX_RECENT) recent.pollLast()
    }

    private fun toSnapshot(sbn: StatusBarNotification): NotificationSnapshot {
        val n = sbn.notification
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            .ifBlank {
                extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            }
        val sub = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        return NotificationSnapshot(
            key = sbn.key ?: "${sbn.packageName}:${sbn.id}",
            packageName = sbn.packageName.orEmpty(),
            title = title,
            text = text,
            subText = sub,
            category = n.category.orEmpty(),
            postTime = sbn.postTime,
            isOngoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            isClearable = sbn.isClearable,
            id = sbn.id,
        )
    }

    companion object {
        private const val TAG = "NotifListener"
        private const val MAX_RECENT = 100

        @Volatile
        var instance: GrokadileNotificationListenerService? = null
            private set

        fun isAuthorized(context: Context): Boolean {
            val flat = ComponentName(context, GrokadileNotificationListenerService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
        }
    }
}

@Singleton
class LiveNotificationContentProvider @Inject constructor() : NotificationContentProvider {
    private fun svc() = GrokadileNotificationListenerService.instance

    override fun isAvailable(): Boolean = svc()?.isAvailable() == true

    override fun activeNotifications(limit: Int): List<NotificationSnapshot> =
        svc()?.activeNotifications(limit) ?: emptyList()

    override fun recentNotifications(limit: Int): List<NotificationSnapshot> =
        svc()?.recentNotifications(limit) ?: emptyList()

    override fun findMatching(
        packageFilter: String?,
        titleContains: String?,
        textContains: String?,
        activeOnly: Boolean,
        limit: Int,
    ): List<NotificationSnapshot> =
        svc()?.findMatching(packageFilter, titleContains, textContains, activeOnly, limit)
            ?: emptyList()
}
