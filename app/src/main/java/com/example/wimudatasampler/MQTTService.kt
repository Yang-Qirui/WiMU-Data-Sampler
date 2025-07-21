package com.example.wimudatasampler
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import io.ktor.client.*
import io.ktor.client.engine.android.Android
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.*
import info.mqtt.android.service.MqttAndroidClient


object MqttClient {

    private const val TAG = "MqttPushClient-Paho"
    // --- Paho 的 WebSocket URI 格式是 "ws://" 或 "wss://" ---
    private const val MQTT_SERVER_URI = "ws://limcpu1.cse.ust.hk:7860/mqttbroker/"
    private const val API_BASE_URL = "http://limcpu1.cse.ust.hk:7860/mqttapi"
    private const val PREFS_NAME = "MqttPrefs"
    private const val KEY_MQTT_USERNAME = "mqtt_username"
    private const val KEY_MQTT_PASSWORD = "mqtt_password"

    // --- Paho 客户端实例 ---
    private var pahoMqClient: MqttAndroidClient? = null

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val ktorHttpClient = HttpClient(Android)

    fun initialize(context: Context) {
        if (this::appContext.isInitialized) {
            Log.w(TAG, "MqttClient is already initialized.")
            return
        }
        this.appContext = context.applicationContext
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            connect()
        }
    }

    private suspend fun connect() {
        if (pahoMqClient?.isConnected == true) {
            Log.d(TAG, "Already connected.")
            return
        }
        val creds = getCredentialsFromPrefs() ?: fetchCredentialsFromServer()?.also {
            saveCredentialsToPrefs(it)
        }
        if (creds == null) {
            Log.e(TAG, "Failed to get credentials. Aborting connection.")
            return
        }
        setupAndConnectMqtt(creds)
    }

    private fun setupAndConnectMqtt(credentials: MqttCredentials) {
        val clientId = getDeviceId()

        // 1. 创建 Paho MqttAndroidClient 实例
        pahoMqClient = MqttAndroidClient(appContext, MQTT_SERVER_URI, clientId)

        // 2. 设置回调来处理连接丢失和消息到达
        pahoMqClient?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "Paho MQTT Connection Complete. Reconnect: $reconnect")
                // 连接成功后，订阅主题
                subscribeToTopic(credentials.mqttUsername)
            }

            override fun connectionLost(cause: Throwable?) {
                Log.e(TAG, "Paho MQTT Connection Lost!", cause)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload)
                Log.i(TAG, "Message arrived from '$topic': $payload")
                // TODO: 在这里处理收到的指令
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })

        // 3. 设置连接选项
        val options = MqttConnectOptions().apply {
            userName = credentials.mqttUsername
            password = credentials.mqttPassword.toCharArray()
            isAutomaticReconnect = true // 开启 Paho 内置的自动重连
            isCleanSession = true
        }

        // 4. 发起连接，并使用 Listener 回调处理结果
        try {
            Log.d(TAG, "Attempting to connect with Paho...")
            pahoMqClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Paho MQTT Connection Success.")
                    // 注意：真正的订阅操作应该在 MqttCallbackExtended.connectComplete 中进行
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Paho MQTT Connection Failure!", exception)
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Paho MQTT Connection Exception", e)
        }
    }

    private fun subscribeToTopic(username: String) {
        val topic = "devices/$username/commands"
        try {
            // 使用 Listener 回调处理订阅结果
            pahoMqClient?.subscribe(topic, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Successfully subscribed to topic: $topic")
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to topic: $topic", exception)
                }
            })
        } catch (e: MqttException) {
            Log.e(TAG, "Error subscribing to topic", e)
        }
    }

    // --- 网络和辅助方法保持不变 ---

    private suspend fun fetchCredentialsFromServer(): MqttCredentials? {
        return try {
            val deviceId = getDeviceId()
            val requestBody = RegisterDeviceRequest(deviceId)
            val jsonStringBody = Json.encodeToString(requestBody)
            val response = ktorHttpClient.post("$API_BASE_URL/register") {
                contentType(ContentType.Application.Json)
                setBody(jsonStringBody)
            }
            if (response.status == HttpStatusCode.OK) {
                val responseString = response.bodyAsText()
                Json.decodeFromString<MqttCredentials>(responseString)
            } else {
                Log.e(TAG, "API call failed with status: ${response.status} and body: ${response.bodyAsText()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ktor API call failed with exception", e)
            null
        }
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceId(): String =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)

    private fun saveCredentialsToPrefs(credentials: MqttCredentials) {
        prefs.edit(commit = true) {
            putString(KEY_MQTT_USERNAME, credentials.mqttUsername)
            putString(KEY_MQTT_PASSWORD, credentials.mqttPassword)
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
            ktorHttpClient.close()
            pahoMqClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnection", e)
        }
    }

    @Serializable
    data class RegisterDeviceRequest(val deviceId: String)
    @Serializable
    data class MqttCredentials(val mqttUsername: String, val mqttPassword: String)
}