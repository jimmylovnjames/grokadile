package com.grokadile.permission

/** The set of OS permissions/special-access grants the app actively manages. */
enum class PermissionType {
    /** Runtime POST_NOTIFICATIONS (API 33+); required for the FGS notification. */
    NOTIFICATIONS,

    /** SYSTEM_ALERT_WINDOW special access; for an on-screen agent HUD. */
    OVERLAY,

    /** Exemption from Doze battery optimization; helps long-running agents. */
    BATTERY_OPTIMIZATION,

    /** BIND_ACCESSIBILITY_SERVICE; only needed for screen-acting agents. */
    ACCESSIBILITY,
}

data class PermissionStatus(
    val type: PermissionType,
    val granted: Boolean,
    /** False for capabilities the user can opt into but that aren't mandatory. */
    val required: Boolean,
)
