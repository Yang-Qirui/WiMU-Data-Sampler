package com.example.wimudatasampler.DataClass

import kotlinx.serialization.Serializable

@Serializable
data class WifiEntry(
    val timestamp: Long,
    val bssid: String,
    val ssid: String?,  // SSID可以为空
    val frequency: Int,
    val rssi: Int
)

@Serializable
data class Coordinate(
    val x: Float,
    val y: Float
)