package com.grokadile.core.device

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface DeviceInfoProvider {
    val deviceId: String
    val label: String
    fun meta(): Map<String, String>
}

@Singleton
class DeviceIdentity @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceInfoProvider {
    override val deviceId: String by lazy { resolveId() }

    override val label: String by lazy {
        buildString {
            append(Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            append(' ')
            append(Build.MODEL)
        }.trim().ifBlank { "Android device" }
    }

    override fun meta(): Map<String, String> = mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "sdk" to Build.VERSION.SDK_INT.toString(),
        "release" to Build.VERSION.RELEASE,
    )

    @SuppressLint("HardwareIds")
    private fun resolveId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            return "android-$androidId"
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = "uuid-${UUID.randomUUID()}"
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }

    companion object {
        private const val PREFS = "grokadile_device"
        private const val KEY = "device_id"
    }
}
