package com.example.wimudatasampler.DataClass

import kotlinx.serialization.Serializable

@Serializable
data class UnifiedSensorData(
    val deviceId: String,
    val timestamp: Long,
    val imu: ImuData?,
    val wifiScans: List<WifiScanResult>?,
    val bleScans: List<BleScanResult>?
)

@Serializable
data class ImuData(
    val displacement: Pair<Float, Float>,
    val steps: Int,
)

@Serializable
data class WifiScanResult(
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val timestamp: Long
)

@Serializable
data class BleScanResult(
    val macAddress: String,
    val rssi: Int,
    val txPower: Int,
    val timestamp: Long
)