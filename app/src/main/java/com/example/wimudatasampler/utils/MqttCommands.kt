package com.example.wimudatasampler.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MqttData(
    val command: String,
    val data: Map<String, JsonElement> = emptyMap()
)

// 定义一个接口，让其他组件（如 Activity/ViewModel/Service）来实现
// MqttClient 将通过这个接口与它们通信，实现了很好的解耦
interface MqttCommandListener {
    fun onStartSampling()
    fun onStopSampling()
    fun onStartInference()
    fun onStopInference()
    fun onGetInferenceResult(x: Float, y: Float)
    fun onUnknownCommand(command: String)
    fun onCommandError(payload: String, error: Throwable)
}