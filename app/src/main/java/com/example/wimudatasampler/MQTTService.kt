package com.example.wimudatasampler // 确保这个包名和你的项目一致

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.hivemq.client.mqtt.MqttClientState
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck
import io.ktor.client.*
import io.ktor.client.engine.android.Android
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.future.await // 导入 await() 扩展函数
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

object MqttClient {
    private const val TAG = "MqttPushClient-HiveMQ"
    private const val MQTT_HOST_ADDRESS = "limcpu1.cse.ust.hk"
    private const val MQTT_PORT = 7860
    private const val MQTT_SERVER_PATH = "mqttbroker"
    private const val API_BASE_URL = "http://limcpu1.cse.ust.hk:7860/mqttapi"
    private const val PREFS_NAME = "MqttPrefs"
    private const val KEY_MQTT_USERNAME = "mqtt_username"
    private const val KEY_MQTT_PASSWORD = "mqtt_password"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var hiveMqClient: Mqtt5AsyncClient? = null

    private val ktorHttpClient = HttpClient(Android)

    /**
     * 初始化 MQTT 客户端。必须在 Application 的 a's'a's a's'onCreate' a's'a's'中调用一次。
     * @param context 应用程序上下文。
     */
    fun initialize(context: Context) {
        if (this::appContext.isInitialized) {
            Log.w(TAG, "MqttClient is already initialized.")
            return
        }
        this.appContext = context.applicationContext // 使用 application context 避免内存泄漏
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 在后台线程启动连接过程
        CoroutineScope(Dispatchers.IO).launch {
            connect()
        }
    }

    private suspend fun connect() {
        if (hiveMqClient?.state == MqttClientState.CONNECTED) {
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

    private suspend fun setupAndConnectMqtt(credentials: MqttCredentials) {
        val clientId = getDeviceId()

        // 使用核心库的构建器创建客户端
        hiveMqClient = Mqtt5Client.builder()
            .identifier(clientId)
            .serverHost(MQTT_HOST_ADDRESS)
            .serverPort(MQTT_PORT)
            .webSocketConfig()
            .serverPath(MQTT_SERVER_PATH)
            .applyWebSocketConfig()
            .addConnectedListener { Log.i(TAG, "HiveMQ client is now connected.") }
            .addDisconnectedListener { context ->
                Log.w(TAG, "HiveMQ client disconnected.", context.cause)
                // 你可以在这里添加自动重连逻辑，尽管 HiveMQ 内部也有重连机制
            }
            .buildAsync()

        try {
            Log.d(TAG, "Attempting to connect with HiveMQ...")
            val connAck: Mqtt5ConnAck = hiveMqClient!!.connectWith()
                .simpleAuth()
                .username(credentials.mqttUsername)
                .password(credentials.mqttPassword.toByteArray())
                .applySimpleAuth()
                .send()
                .await() // 挂起协程，等待连接结果

            if (connAck.reasonCode.isError) {
                Log.e(TAG, "MQTT Connection failed: ${connAck.reasonString}")
                return
            }

            Log.i(TAG, "MQTT Connection Success.")

            // 连接成功后, 设置回调并订阅
            setupMessageCallback()
            subscribeToTopic(credentials.mqttUsername)

        } catch (e: Exception) {
            Log.e(TAG, "MQTT Connection Exception", e)
        }
    }

    private fun setupMessageCallback() {
        hiveMqClient?.publishes(MqttGlobalPublishFilter.ALL) { mqtt5Publish ->
            val topic = mqtt5Publish.topic.toString()
            val payload = StandardCharsets.UTF_8.decode(mqtt5Publish.payload.get()).toString()
            Log.i(TAG, "Message arrived from '$topic': $payload")
            // TODO: 在这里处理收到的指令, e.g., YourCommandHandler.handle(payload)
            // 如果需要更新UI，请切换到主线程
        }
    }

    private suspend fun subscribeToTopic(username: String) {
        val topic = "devices/$username/commands"
        try {
            val subAck = hiveMqClient?.subscribeWith()
                ?.topicFilter(topic)
                ?.qos(MqttQos.AT_LEAST_ONCE)
                ?.send()
                ?.await()

            subAck?.reasonCodes?.forEachIndexed { index, reasonCode ->
                if (reasonCode.isError) {
                    Log.w(TAG, "Subscription to topic filter $index failed with reason: $reasonCode")
                } else {
                    Log.i(TAG, "Subscription to topic filter $index successful with QoS: $reasonCode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Subscription to '$topic' failed with exception", e)
        }
    }

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
            hiveMqClient?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnection", e)
        }
    }

    @Serializable
    data class RegisterDeviceRequest(val deviceId: String)

    @Serializable
    data class MqttCredentials(val mqttUsername: String, val mqttPassword: String)
}