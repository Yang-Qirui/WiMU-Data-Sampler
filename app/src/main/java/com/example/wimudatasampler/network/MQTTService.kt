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
import com.example.wimudatasampler.Config.KEY_MQTT_PASSWORD
import com.example.wimudatasampler.Config.KEY_MQTT_USERNAME
import com.example.wimudatasampler.Config.PREFS_NAME
import com.example.wimudatasampler.utils.getDeviceId
import com.example.wimudatasampler.utils.getDeviceName

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

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    fun initialize(context: Context, warehouseName:String, mqttServerUrl: String, apiBaseUrl:String) {
        if (this::appContext.isInitialized) {
            Log.w(TAG, "MqttClient is already initialized.")
            return
        }
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            connect(warehouseName = warehouseName, mqttServerUrl = mqttServerUrl, apiBaseUrl = apiBaseUrl)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private suspend fun connect(warehouseName: String, mqttServerUrl:String, apiBaseUrl:String) {
        if (pahoMqClient?.isConnected == true) {
            Log.d(TAG, "Already connected.")
            return
        }
        val creds = getCredentialsFromPrefs() ?: fetchCredentialsFromServer(warehouseName = warehouseName, apiBaseUrl = apiBaseUrl)?.also {
            saveCredentialsToPrefs(it)
        }
        if (creds == null) {
            Log.e(TAG, "Failed to get credentials. Aborting connection.")
            return
        }
        setupAndConnectMqtt(credentials = creds, mqttServerUrl = mqttServerUrl)
    }

    private fun setupAndConnectMqtt(credentials: MqttCredentials, mqttServerUrl:String) {
        val clientId = getDeviceId(appContext)

        // 1. 创建 Paho MqttAndroidClient 实例
        pahoMqClient = MqttAndroidClient(appContext, mqttServerUrl, clientId)

        // 2. 设置回调来处理连接丢失和消息到达
        pahoMqClient?.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                Log.i(TAG, "Paho MQTT Connection Complete. Reconnect: $reconnect")
                // 连接成功后，订阅主题
                subscribeToTopic("devices/${credentials.mqtt_username}/commands")
//                subscribeToTopic("devices/inference")
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
//                    Log.d("MQTT res", result)
                    val command = result.command
                    Log.d("Command", command)
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
                            commandListener?.onGetInferenceResult(x.toFloat(), y.toFloat()) //TODO：需要修改
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
            userName = credentials.mqtt_username
            password = credentials.mqtt_password.toCharArray()
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
    private suspend fun fetchCredentialsFromServer(warehouseName: String, apiBaseUrl:String): MqttCredentials? {
        return try {
            val deviceId = getDeviceId(appContext)
            val deviceName = getDeviceName(appContext)
            Log.d("deviceName", deviceName)
            val response = ktorHttpClient.post("$apiBaseUrl/device/register") {
                parameter("device_id", deviceId)
                parameter("device_name", deviceName)
                parameter("warehouse_id", warehouseName) // TODO: Dynamically change warehouse
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
            putString(KEY_MQTT_USERNAME, credentials.mqtt_username)
            putString(KEY_MQTT_PASSWORD, credentials.mqtt_password)
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

    // 新增：用于清除 SharedPreferences 中存储的 MQTT 凭证
    private fun clearCredentialsFromPrefs() {
        Log.d(TAG, "Clearing stored MQTT credentials.")
        prefs.edit(commit = true) {
            remove(KEY_MQTT_USERNAME)
            remove(KEY_MQTT_PASSWORD)
        }
    }

    // 新增：核心的重新注册和连接方法
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    suspend fun reRegisterAndConnect(warehouseName:String, mqttServerUrl: String, apiBaseUrl: String) {
        // 0. 检查 MqttClient 是否已初始化
        if (!this::appContext.isInitialized) {
            Log.e(TAG, "MqttClient has not been initialized. Cannot re-register.")
            return
        }

        Log.i(TAG, "Starting re-registration process...")

        // 1. 断开现有连接（如果存在）
        // disconnect() 方法会关闭客户端并将其设为 null
        disconnect()
        Log.d(TAG, "Previous connection disconnected.")

        // 2. 清除旧的凭证
        clearCredentialsFromPrefs()

        // 3. 调用现有的 connect 方法
        // 由于凭证已被清除，`connect` 方法会自动从服务器获取新凭证
        Log.d(TAG, "Proceeding to connect with new credentials...")
        connect(warehouseName = warehouseName, mqttServerUrl = mqttServerUrl, apiBaseUrl = apiBaseUrl)
    }

//    fun disconnect() {
//        try {
//            ktorHttpClient.close()
//            pahoMqClient?.disconnect()
//            pahoMqClient = null
//        } catch (e: Exception) {
//            Log.e(TAG, "Error during disconnection", e)
//        }
//    }

//    --- 确保你的 disconnect 方法是这样的 ---
    fun disconnect() {
        try {
            // 先尝试断开 Paho 客户端的连接
            if (pahoMqClient?.isConnected == true) {
                pahoMqClient?.disconnect()
                Log.i(TAG, "MQTT client disconnected.")
            }
            // 关闭 Ktor HttpClient
            ktorHttpClient.close()
            // 将客户端实例置为 null，以便下次可以创建新的实例
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
    data class MqttCredentials(val mqtt_username: String, val mqtt_password: String)
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