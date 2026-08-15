package com.grokadile.domain.agent

data class DeviceHealth(
    val batteryPercent: Int,
    val charging: Boolean,
    val networkConnected: Boolean,
    val networkType: String,
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    val deviceId: String,
    val label: String,
    val sdk: Int,
    val release: String,
)

/** Live device vitals for the health agent and dashboard. */
interface DeviceHealthProvider {
    fun snapshot(): DeviceHealth
}
