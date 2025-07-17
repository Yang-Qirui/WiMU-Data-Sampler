package com.example.wimudatasampler

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import androidx.core.content.edit

object MqttClient {

    private const val TAG = "MqttPushClient"
    private const val MQTT_HOST = "tcp://limcpu1.cse.ust.hk:7860/mqttbroker" // TODO: change ip
    private const val API_HOST = "http://limcpu1.cse.ust.hk:7860/mqttapi" // TODO: change ip
    private const val PREFS_NAME = "MqttPrefs"
    private const val KEY_MQTT_USERNAME = "mqtt_username"
    private const val KEY_MQTT_PASSWORD = "mqtt_password"

    private var mqttClient: MqttAndroidClient? = null
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    // 1. 初始化 Ktor HttpClient
    private val ktorHttpClient = HttpClient(CIO)

    fun initialize(context: Context) {
        this.appContext = context
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            connect()
        }
    }

    private suspend fun connect() {
        if (mqttClient?.isConnected == true) {
            Log.d(TAG, "Already connected.")
            return
        }
        var creds = getCredentialsFromPrefs()
        if (creds == null) {
            Log.i(TAG, "No local credentials found. Fetching from server with Ktor...")
            creds = fetchCredentialsFromServer() // <--- 使用 Ktor 的新实现
            if (creds != null) {
                saveCredentialsToPrefs(creds)
            } else {
                Log.e(TAG, "Failed to get credentials. Aborting connection.")
                return
            }
        }
        setupAndConnectMqtt(creds)
    }

    // 2. fetchCredentialsFromServer 的 Ktor 实现
    private suspend fun fetchCredentialsFromServer(): MqttCredentials? {
        return try {
            val deviceId = getDeviceId()
            val requestBody = RegisterDeviceRequest(deviceId)

            // Ktor 的 POST 请求，非常简洁
            val response = ktorHttpClient.post(API_HOST + "/register") {
                setBody(requestBody)
            }

            // 自动将响应体解析为 MqttCredentials 对象
            response.body<MqttCredentials>()
        } catch (e: Exception) {
            // Ktor 会将网络错误和HTTP状态码错误 (4xx, 5xx) 都作为异常抛出
            Log.e(TAG, "Ktor API call failed", e)
            null
        }
    }

    // --- setupAndConnectMqtt 和其他辅助方法保持不变 ---

    private fun setupAndConnectMqtt(credentials: MqttCredentials) {
        val clientId = getDeviceId()
        mqttClient = MqttAndroidClient(appContext, MQTT_HOST, clientId)

        mqttClient?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "MQTT Connection Complete. Reconnect: $reconnect")
                subscribeToTopic(credentials.mqttUsername)
            }
            override fun connectionLost(cause: Throwable?) {
                Log.e(TAG, "MQTT Connection Lost!", cause)
            }
            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload)
                Log.i(TAG, "Message arrived from '$topic': $payload")
                // TODO: Handle the command payload here
            }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        val options = MqttConnectOptions().apply {
            userName = credentials.mqttUsername
            password = credentials.mqttPassword.toCharArray()
            isAutomaticReconnect = true
            isCleanSession = true
        }

        try {
            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) { Log.i(TAG, "MQTT Connection Success.") }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) { Log.e(TAG, "MQTT Connection Failure!", exception) }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Error connecting MQTT", e)
        }
    }

    private fun subscribeToTopic(username: String) {
        val topic = "devices/$username/commands"
        try {
            mqttClient?.subscribe(topic, 1)
            Log.i(TAG, "Subscribed to topic: $topic")
        } catch (e: MqttException) {
            Log.e(TAG, "Error subscribing", e)
        }
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceId(): String =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)

    private fun saveCredentialsToPrefs(credentials: MqttCredentials) {
        prefs.edit {
            putString(KEY_MQTT_USERNAME, credentials.mqttUsername)
                .putString(KEY_MQTT_PASSWORD, credentials.mqttPassword)
        }
    }

    private fun getCredentialsFromPrefs(): MqttCredentials? {
        val username = prefs.getString(KEY_MQTT_USERNAME, null)
        val password = prefs.getString(KEY_MQTT_PASSWORD, null)
        return if (username != null && password != null) {
            MqttCredentials(username, password)
        } else {
            null
        }
    }

    fun disconnect() {
        try {
            // 3. 关闭 Ktor client 释放资源
            ktorHttpClient.close()
            mqttClient?.disconnect()
        } catch (e: MqttException) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }

    // --- 数据类 ---
    // 用于 Ktor 自动序列化/反序列化
    data class RegisterDeviceRequest(val deviceId: String)
    data class MqttCredentials(val mqttUsername: String, val mqttPassword: String)
}