package com.example.wimudatasampler
import androidx.compose.ui.geometry.Offset

// 这个数据类包含了所有需要被UI展示的状态
data class NavigationUiState(
    // Sensor data for UI
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val orientation: Float = 0f, // adjustedDegrees
    val mag: Float = 0f,
    val accX: Float = 0f,
    val accY: Float = 0f,
    val accZ: Float = 0f,

    // Navigation state
    val targetOffset: Offset = Offset.Zero,
    val imuOffset: Offset? = Offset.Zero, // Start with a non-null value for simplicity
    val wifiOffset: Offset? = null,

    // Status indicators
    val navigationStarted: Boolean = false,
    val loadingStarted: Boolean = false,
    val isMonitoringAngles: Boolean = false,

    // Step counting and stride
    val stepFromMyDetector: Float = 0f,
    val estimatedStride: Float = 0f,

    // Control flags that UI might need to know
    val enableImu: Boolean = true,
    val enableMyStepDetector: Boolean = false,
)