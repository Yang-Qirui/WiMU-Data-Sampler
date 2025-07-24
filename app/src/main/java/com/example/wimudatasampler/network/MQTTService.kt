package com.example.wimudatasampler.network
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.example.wimudatasampler.utils.MqttCommandListener
import com.example.wimudatasampler.utils.MqttData
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
import kotlinx.serialization.json.Json.Default.decodeFromString
import kotlinx.serialization.json.jsonPrimitive
import com.example.wimudatasampler.Config.API_BASE_URL
import com.example.wimudatasampler.Config.KEY_MQTT_PASSWORD
import com.example.wimudatasampler.Config.KEY_MQTT_USERNAME
import com.example.wimudatasampler.Config.MQTT_SERVER_URI
import com.example.wimudatasampler.Config.PREFS_NAME
import com.example.wimudatasampler.utils.getDeviceId
import com.example.wimudatasampler.utils.getDeviceName
import info.mqtt.android.service.Ack

object MqttClient {

    const val TAG = "MqttPushClient-Paho"
    // --- Paho 的 WebSocket URI 格式是 "ws://" 或 "wss://" ---

    @SuppressLint("StaticFieldLeak")
    var pahoMqClient: MqttAndroidClient? = null
    private var commandListener: MqttCommandListener? = null

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private val ktorHttpClient = HttpClient(Android)

    fun setCommandListener(listener: MqttCommandListener?) {
        Log.d(TAG, "Setting MqttCommandListener.")
        commandListener = listener
    }

    fun initialize(context: Context) {
        if (this::appContext.isInitialized) {
            Log.w(TAG, "MqttClient is already initialized.")
            return
        }
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        val clientId = getDeviceId(appContext)

        // 1. 创建 Paho MqttAndroidClient 实例
        pahoMqClient = MqttAndroidClient(appContext, MQTT_SERVER_URI, clientId)

        // 2. 设置回调来处理连接丢失和消息到达
        pahoMqClient?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "Paho MQTT Connection Complete. Reconnect: $reconnect")
                // 连接成功后，订阅主题
                subscribeToTopic("devices/${credentials.mqttUsername}/commands")
                subscribeToTopic("devices/inference")
                subscribeToTopic("devices/ack")
            }

            override fun connectionLost(cause: Throwable?) {
                Log.e(TAG, "Paho MQTT Connection Lost!", cause)
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = String(message.payload)
                Log.i(TAG, "Message arrived from '$topic': $payload")
                try {
                    val result = decodeFromString<MqttData>(payload)
                    val command = result.data["command"]?.jsonPrimitive?.content!!
                    when (command) {
                        "end_sample" -> {
                            commandListener?.onStopSampling()
                        }
                        "end_inference" -> {
                            commandListener?.onStopInference()
                        }
                        "start_sample" -> {
                            commandListener?.onStartSampling()
                        }
                        "start_inference" -> {
                            commandListener?.onStartInference()
                        }
                        "inference_result" -> {
                            val x = result.data["x"]?.jsonPrimitive?.content!!
                            val y = result.data["y"]?.jsonPrimitive?.content!!
                            commandListener?.onGetInferenceResult(x.toFloat(), y.toFloat())
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, e.toString())
                }
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

    private fun subscribeToTopic(topic: String) {
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

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private suspend fun fetchCredentialsFromServer(): MqttCredentials? {
        return try {
            val deviceId = getDeviceId(appContext)
            val deviceName = getDeviceName(appContext)
            Log.d("deviceName", deviceName)
            val requestBody = RegisterDeviceRequest(deviceId, deviceName)
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
            pahoMqClient = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnection", e)
        }
    }

    inline fun <reified T>publishData(topicSuffix: String, data: T, qos: Int = 1, retained: Boolean = false) {
        // 1. 检查客户端是否已连接
        if (pahoMqClient?.isConnected != true) {
            Log.e(TAG, "Cannot publish data, MQTT client is not connected.")
            return
        }

        val fullTopic = "devices/$topicSuffix"

        try {
            // 3. 使用 Kotlinx.serialization 将数据对象序列化为 JSON 字符串
            val payloadString = Json.encodeToString(data)

            // 4. 创建 MqttMessage
            val mqttMessage = MqttMessage(payloadString.toByteArray()).apply {
                this.qos = qos
                this.isRetained = retained
            }

            // 5. 使用 Paho 客户端发布消息
            pahoMqClient?.publish(fullTopic, mqttMessage, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Successfully published message to '$fullTopic'. Payload: $payloadString")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to publish message to '$fullTopic'", exception)
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error during serialization or publishing", e)
        }
    }

    @Serializable
    data class RegisterDeviceRequest(val deviceId: String, val deviceName: String)
    @Serializable
    data class MqttCredentials(val mqttUsername: String, val mqttPassword: String)
    @Serializable
    data class InferenceData(
        val deviceId: String,
        val wifiList: List<String>,
        val imuOffset: Pair<Float, Float>?,
        val sysNoise: Float,
        val obsNoise: Float
    )
    @Serializable
    data class AckData(
        val deviceId: String,
        val ackInfo: String
    )
}