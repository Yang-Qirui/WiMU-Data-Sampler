package com.example.wimudatasampler.DataClass

import kotlinx.serialization.Serializable
import java.io.Serial

@Serializable
data class DataEntry(
    val timestamp: Long,
    val bssid: String,
    val ssid: String?,  // SSID可以为空
    val frequency: Int,
    val rssi: Int
)

@Serializable
data class RequestData(
    val wifiEntries: List<DataEntry>,
    val dx: Float?,
    val dy: Float?,
    val system_noise_scale: Float,
    val obs_noise_scale: Float
)

@Serializable
data class Coordinate(
    val x: Float,
    val y: Float
)