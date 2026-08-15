package com.grokadile.domain.agent

/**
 * Bridge that lets notification-capable agents observe the notification shade
 * without depending on Android framework types. Implemented by the live
 * [com.grokadile.service.GrokadileNotificationListenerService] (or a test fake).
 *
 * All methods are safe to call from any thread.
 */
interface NotificationContentProvider {

    /** True when the NotificationListenerService is connected and authorized. */
    fun isAvailable(): Boolean

    /**
     * Active (posted, not yet dismissed) notifications, newest first.
     * @param limit max entries to return
     */
    fun activeNotifications(limit: Int = 50): List<NotificationSnapshot>

    /**
     * Recent notifications including dismissed ones still in the ring buffer.
     */
    fun recentNotifications(limit: Int = 50): List<NotificationSnapshot>

    /**
     * Filter helpers — matchers are case-insensitive substring unless empty.
     */
    fun findMatching(
        packageFilter: String? = null,
        titleContains: String? = null,
        textContains: String? = null,
        activeOnly: Boolean = true,
        limit: Int = 50,
    ): List<NotificationSnapshot>
}

/**
 * Platform-agnostic snapshot of a single notification.
 */
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val subText: String = "",
    val category: String = "",
    val postTime: Long,
    val isOngoing: Boolean = false,
    val isClearable: Boolean = true,
    val id: Int = 0,
) {
    fun matches(
        packageFilter: String?,
        titleContains: String?,
        textContains: String?,
    ): Boolean {
        if (!packageFilter.isNullOrBlank() &&
            !packageName.contains(packageFilter, ignoreCase = true)
        ) return false
        if (!titleContains.isNullOrBlank() &&
            !title.contains(titleContains, ignoreCase = true)
        ) return false
        if (!textContains.isNullOrBlank() &&
            !text.contains(textContains, ignoreCase = true) &&
            !title.contains(textContains, ignoreCase = true)
        ) return false
        return true
    }

    fun toCompactLine(): String =
        "[$packageName] $title — $text".trimEnd(' ', '—')
}
