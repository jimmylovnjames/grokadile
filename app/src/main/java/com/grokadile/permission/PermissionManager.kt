package com.grokadile.permission

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.grokadile.service.GrokadileAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the app's special permissions: pure status checks
 * plus the Intents needed to send the user to the right settings surface.
 * Runtime dialog requests (POST_NOTIFICATIONS) are driven from Compose via an
 * ActivityResult launcher; everything else routes through these Intents.
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // --- status ------------------------------------------------------------

    fun isNotificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(context)

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(context, GrokadileAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun isGranted(type: PermissionType): Boolean = when (type) {
        PermissionType.NOTIFICATIONS -> isNotificationsGranted()
        PermissionType.OVERLAY -> isOverlayGranted()
        PermissionType.BATTERY_OPTIMIZATION -> isBatteryOptimizationIgnored()
        PermissionType.ACCESSIBILITY -> isAccessibilityEnabled()
    }

    fun snapshot(): List<PermissionStatus> = PermissionType.entries.map { type ->
        PermissionStatus(
            type = type,
            granted = isGranted(type),
            required = type == PermissionType.NOTIFICATIONS,
        )
    }

    // --- intents -----------------------------------------------------------

    fun settingsIntentFor(type: PermissionType): Intent = when (type) {
        PermissionType.NOTIFICATIONS -> appNotificationSettingsIntent()
        PermissionType.OVERLAY -> overlaySettingsIntent()
        PermissionType.BATTERY_OPTIMIZATION -> batteryOptimizationIntent()
        PermissionType.ACCESSIBILITY -> accessibilitySettingsIntent()
    }

    private fun overlaySettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    private fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun appNotificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /**
     * Direct "ignore battery optimizations" request. Subject to Play policy;
     * for store builds prefer routing the user to the optimization settings
     * list ([Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS]) instead.
     */
    @Suppress("BatteryLife")
    private fun batteryOptimizationIntent(): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
}
