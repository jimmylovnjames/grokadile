package com.grokadile.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.grokadile.core.device.DeviceInfoProvider
import com.grokadile.domain.agent.DeviceHealth
import com.grokadile.domain.agent.DeviceHealthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveDeviceHealthProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: DeviceInfoProvider,
) : DeviceHealthProvider {

    override fun snapshot(): DeviceHealth {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(0, 100)
        val charging = battery.isCharging ||
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ==
            BatteryManager.BATTERY_STATUS_CHARGING

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            connected -> "other"
            else -> "none"
        }

        val stat = StatFs(context.filesDir.absolutePath)
        return DeviceHealth(
            batteryPercent = percent,
            charging = charging,
            networkConnected = connected,
            networkType = type,
            freeStorageBytes = stat.availableBytes,
            totalStorageBytes = stat.totalBytes,
            deviceId = identity.deviceId,
            label = identity.label,
            sdk = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE ?: "",
        )
    }
}
