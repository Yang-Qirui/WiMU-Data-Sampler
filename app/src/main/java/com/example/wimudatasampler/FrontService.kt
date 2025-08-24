package com.example.wimudatasampler

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.core.app.NotificationCompat
import com.example.wimudatasampler.Config.API_BASE_URL
import com.example.wimudatasampler.Config.MQTT_SERVER_URI
import com.example.wimudatasampler.DataClass.BleScanResult
import com.example.wimudatasampler.DataClass.ImuData
import com.example.wimudatasampler.DataClass.UnifiedSensorData
import com.example.wimudatasampler.DataClass.WifiScanResult
import com.example.wimudatasampler.network.MqttClient
import com.example.wimudatasampler.network.MqttClient.AckData
import com.example.wimudatasampler.network.MqttClient.publishData
import com.example.wimudatasampler.utils.calculateTotalDisplacement
import com.example.wimudatasampler.utils.MqttCommandListener
import com.example.wimudatasampler.utils.SensorUtils
import com.example.wimudatasampler.utils.UserPreferencesKeys
import com.example.wimudatasampler.utils.lowPassFilter
import com.example.wimudatasampler.utils.getDeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class ServiceState(
    //需要暴露给软件UI进行显示的变量
    //UI State
    var isReRegistering: Boolean = false, // <--- 新增这一行
    var isCollectTraining: Boolean = false,
    var isSampling: Boolean = false,
    var isLocatingStarted: Boolean = false,
    var isLoadingStarted: Boolean = false,
    var isImuEnabled: Boolean = true,
    var isMyStepDetectorEnabled: Boolean = false, //TODO: use own step detector
    var useBleLocating: Boolean = false,
    var isBluetoothTimeWindowEnabled: Boolean = false, // 默认开启
    var bluetoothTimeWindowSeconds: Float = 0.5F,     // 默认0.5秒 (500毫秒)
    //Sampling Page Data
    val yaw: Float = 0.0F,
    val pitch: Float = 0.0F,
    val roll: Float = 0.0F,
    var numOfLabelSampling: Int? = null, // Start from 0
    var wifiOrBleScanningInfo: String? = null,
    var wifiOrBleSamplingCycles: Float = 3.0F,
    var sensorSamplingCycles: Float = 0.05F,
    var saveDirectory: String = "",
    var targetOffset: Offset = Offset.Zero, // User's physical location
    val userHeading: Float? = null, // User orientation Angle (0-360)
    val waypoints: SnapshotStateList<Offset> = mutableStateListOf(),
    var imuOffset: Offset = Offset.Zero,
    var cumulatedStep: Int = 0,
    var uploadFlag: Int = 0,
    var uploadOffset: Offset = Offset.Zero,
    var rttLatency: Float = 0f
)

class FrontService : Service(), SensorUtils.SensorDataListener, MqttCommandListener {

    //region 核心服务与组件 (Core Service & Components)
    private lateinit var motionSensorManager: SensorUtils
    lateinit var wifiManager: WifiManager
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner
    private lateinit var deviceId: String
    private val binder = LocationBinder()
    //endregion


    //region 统一调度器 (Unified Scheduler)
    private var schedulerJob: Job? = null
    //endregion


    //region 并发与协程管理 (Concurrency & Coroutine Management)
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob) // 用于通用的、一次性的服务任务
    private val locatingServiceJob = Job()
    private val locatingServiceScope = CoroutineScope(Dispatchers.IO + locatingServiceJob) // 专门用于跑统一调度器
    //endregion


    //region UI状态通信 (UI State Communication)
    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState = _serviceState.asStateFlow()
    //endregion


    //region 资源和引用计数
    // --- 资源枚举 ---
    private enum class SensorResource {
        IMU, WIFI, BLUETOOTH
    }
    // --- 引用计数器 ---
    private val resourceReferenceCounter = ConcurrentHashMap<SensorResource, AtomicInteger>().apply {
        put(SensorResource.IMU, AtomicInteger(0))
        put(SensorResource.WIFI, AtomicInteger(0))
        put(SensorResource.BLUETOOTH, AtomicInteger(0))
    }
    //endregion


    //region 统一数据采集缓冲区 (Unified Data Collection Buffers)
    private val wifiScanBuffer = CopyOnWriteArrayList<WifiScanResult>()
    private val bleScanBuffer = CopyOnWriteArrayList<BleScanResult>()
    private val imuDisplacementBuffer = MutableStateFlow(Offset(0f, 0f))
    private val stepCountBuffer = AtomicInteger(0)
    //endregion


    //region IMU原始数据处理与滤波 (IMU Raw Data Processing & Filtering)
    // --- 用于从传感器获取原始方向 ---
    private var lastRotationVector: FloatArray? = null // 最新获取的旋转矢量传感器数据
    private var rotationMatrix = FloatArray(9)      // 用于从旋转矢量计算方向的中间矩阵

    // --- 用于方向角低通滤波/平滑处理 ---
    /**
     * 平滑系数 (alpha) 用于低通滤波器。
     * 值越小，平滑效果越强，但对方向变化的响应越慢。
     * 新方向 = (alpha * 当前方向) + ((1 - alpha) * 上一次的平滑方向)
     */
    private var alpha = 0.1f

    /**
     * 存储上一次计算出的、经过低通滤波后的平滑方向值。
     * 这是滤波算法中的历史状态。
     */
    private var filteredDirection by mutableFloatStateOf(0f)

    /**
     * 最终调整并准备供UI或其他部分使用的方向角。
     * 如果你只是在内部使用`filteredDirection`，这个变量可能可以和它合并。
     * 这里保留它，以防有特定UI显示需求。
     */
    private var adjustedDegrees by mutableFloatStateOf(0f)
    //endregion


    //region 持久化与可配置参数 (Persistent & Configurable Parameters)
    var stride by mutableFloatStateOf(0.55f)
    var azimuthOffset by mutableFloatStateOf(0.0f)
    var warehouseName = "jd-yayi"

    // 用于连接和上传的服务器地址
    var mqttServerUrl = MQTT_SERVER_URI
    var apiBaseUrl = API_BASE_URL

    // inference相关参数
    var inferencePeriod = 3.0f
    var sysNoise = 1.0f
    var obsNoise = 3.0f

     var beta by mutableFloatStateOf(0.9f)
     var url = "http://limcpu1.cse.ust.hk:7860"
    //endregion


    //region Android Service 标准实现 (Android Service Boilerplate)
    companion object {
        const val ACTION_START = "com.example.wimudatasampler.action.START"
        const val ACTION_STOP = "com.example.wimudatasampler.action.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "LocationServiceChannel"
        private const val NOTIFICATION_ID = 1
        var isServiceRunning = false
    }

    inner class LocationBinder : Binder() {
        fun getService(): FrontService = this@FrontService
    }

    override fun onBind(intent: Intent?): IBinder = binder
    //endregion


    //资源管理器 (Resource Manager)：一个内部逻辑单元，负责根据业务需求启动或停止硬件采集层。它使用引用计数来跟踪有多少个业务正在使用某个硬件。
    //为 wifiTriggerJob 添加一个成员变量
    private var wifiTriggerJob: Job? = null
    //资源管理器 (acquire)
    @SuppressLint("MissingPermission")
    private fun acquireResource(resource: SensorResource) {
        // 原子地增加引用计数，并获取增加前的值
        val previousCount = resourceReferenceCounter[resource]?.getAndIncrement()

        // 如果之前引用计数为 0，意味着这是第一个请求，需要启动硬件
        if (previousCount == 0) {
            Log.d("ResourceManager", "Acquiring resource: $resource. Starting hardware.")
            when (resource) {
                SensorResource.IMU -> motionSensorManager.startMonitoring(this)
                SensorResource.WIFI -> {
                    // 启动一个持续触发Wi-Fi扫描的协程
                    // 将这个job保存起来，以便后续可以停止它
                    wifiTriggerJob = serviceScope.launch {
                        while (isActive) {
                            wifiManager.startScan()
                            delay(3000) // 固定的3秒扫描周期
                        }
                    }
                }
                SensorResource.BLUETOOTH -> startBluetoothScan()
            }
        } else {
            Log.d("ResourceManager", "Resource $resource already active. Incremented count to ${previousCount?.plus(1)}.")
        }
    }

    //资源管理器 (release)
    @SuppressLint("MissingPermission")
    private fun releaseResource(resource: SensorResource) {
        // 原子地减少引用计数，并获取减少后的值
        val newCount = resourceReferenceCounter[resource]?.decrementAndGet()

        // 如果减少后引用计数变为 0，意味着这是最后一个使用者，可以关闭硬件了
        if (newCount == 0) {
            Log.d("ResourceManager", "Releasing resource: $resource. Stopping hardware.")
            when (resource) {
                SensorResource.IMU -> motionSensorManager.stopMonitoring()
                SensorResource.WIFI -> {
                    wifiTriggerJob?.cancel() // 停止Wi-Fi触发器
                    wifiTriggerJob = null
                }
                SensorResource.BLUETOOTH -> stopBluetoothScan()
            }
        } else {
            Log.d("ResourceManager", "Resource $resource still in use. Decremented count to $newCount.")
        }
    }


    // --- Wi-Fi和蓝牙的回调，只做一件事：往缓冲区里添加数据 ---
    private val wifiScanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success && wifiManager.scanResults.isNotEmpty()) {
                serviceScope.launch(Dispatchers.Default) {
                    val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                    val minScanTime = wifiManager.scanResults.minOf { it.timestamp }
                    val maxScanTime = wifiManager.scanResults.maxOf { it.timestamp }
                    Log.d("DIFF", "${maxScanTime - minScanTime}")
                    Log.d("RECEIVED_RESULTS", wifiManager.scanResults.toString())

                    if (maxScanTime - minScanTime < 500_000_000) {
                        val wifiTimestamp = (minScanTime / 1000 + bootTime)
                        val newScans = wifiManager.scanResults.map {
                            WifiScanResult(
                                bssid = it.BSSID,
                                rssi = it.level,
                                frequency = it.frequency,
                                timestamp = wifiTimestamp
                            )
                        }
                        wifiScanBuffer.addAll(newScans)
                        // 根据当前inference模式执行不同的操作
                        when (currentInferenceMode) {
                            InferenceMode.WIFI -> handleWifiModeUpload()
                            InferenceMode.WIFI_ROAD_NETWORK -> handleRoadNetworkWifiUpload()
                            InferenceMode.WIFI_ONLY -> handleWifiOnlyModeUpload()
                            else -> {
                                // 在其他模式下，Wi-Fi扫描结果只被缓存，不触发上传
                            }
                        }
                    }
                }
            }
        }
    }
    // 蓝牙扫描回调辅助函数
    private fun handleBleScanResult(result: ScanResult) {
        // 1. 将原生 ScanResult 转换为我们统一的 BleScanResult 数据模型
        val bleResult = BleScanResult(
            macAddress = result.device.address,
            rssi = result.rssi,
            txPower = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 如果txPower不可用, ScanResult会返回TX_POWER_NOT_PRESENT (127)
                if (result.txPower != 127) result.txPower else -127 // 使用一个特定的值表示不可用
            } else {
                -127 // -127 表示在旧版本上不可用
            },
            timestamp = System.currentTimeMillis() - SystemClock.elapsedRealtime() + (result.timestampNanos / 1_000_000)
        )

        // 2. 将转换后的数据模型添加到线程安全的缓冲区
        // 这个缓冲区是 CopyOnWriteArrayList，所以这里的 add 操作是线程安全的
        bleScanBuffer.add(bleResult)
    }
    // 蓝牙扫描回调
    @SuppressLint("MissingPermission")
    private val leScanCallback = object : ScanCallback() {
        /**
         * 单个扫描结果的回调
         */
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                // 使用一个统一的辅助函数来处理扫描结果，避免代码重复
                handleBleScanResult(it)
            }
        }

        /**
         * 批量扫描结果的回调 (省电模式)
         */
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { result ->
                handleBleScanResult(result)
            }
        }

        /**
         * 扫描失败的回调 (健壮性)
         */
        override fun onScanFailed(errorCode: Int) {
            Log.e("BluetoothScan", "Scan Failed with error code: $errorCode")

            // 保持你原有的健壮性恢复逻辑
            if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                Log.w("BluetoothScan", "Attempting to restart bluetooth scan due to registration failure.")
                stopBluetoothScan()
                // 使用 serviceScope 在 service 的生命周期内重启
                serviceScope.launch {
                    delay(250) // 延迟一小段时间再重启
                    startBluetoothScan()
                }
            }
        }
    }

    // 用于触发重新注册MQTT
    @RequiresApi(Build.VERSION_CODES.N_MR1)
    fun reRegisterMqttClient() {
        // 1. 检查是否已经在执行，防止重复点击
        if (_serviceState.value.isReRegistering) {
            Log.d("FrontService", "Re-registration is already in progress.")
            return
        }

        // 2. 使用 serviceScope 启动一个协程来处理这个耗时操作
        serviceScope.launch {
            // 3. 更新状态，通知 UI 开始加载
            _serviceState.update { it.copy(isReRegistering = true) }

            Log.i("FrontService", "Starting MQTT re-registration process...")

            try {
                // 4. 调用 MqttClient 的核心方法
                //    注意：mqttServerUrl 和 apiBaseUrl 已经是 Service 的成员变量，可以直接使用
                MqttClient.reRegisterAndConnect(
                    warehouseName = warehouseName,
                    mqttServerUrl = this@FrontService.mqttServerUrl,
                    apiBaseUrl = this@FrontService.apiBaseUrl
                )
                Log.i("FrontService", "MQTT re-registration process completed successfully.")
            } catch (e: Exception) {
                // 5. 捕获任何可能的异常
                Log.e("FrontService", "MQTT re-registration failed", e)
            } finally {
                // 6. 任务结束（无论成功或失败），恢复 UI 状态
                _serviceState.update { it.copy(isReRegistering = false) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            dataStore.data.collect { preferences ->
                stride = preferences[UserPreferencesKeys.STRIDE] ?: stride
                beta = preferences[UserPreferencesKeys.BETA] ?: beta
                sysNoise = preferences[UserPreferencesKeys.SYS_NOISE] ?: sysNoise
                obsNoise = preferences[UserPreferencesKeys.OBS_NOISE] ?: obsNoise
                url = preferences[UserPreferencesKeys.URL] ?: url
                mqttServerUrl = preferences[UserPreferencesKeys.MQTT_SERVER_URL] ?: mqttServerUrl
                apiBaseUrl = preferences[UserPreferencesKeys.API_BASE_URL] ?: apiBaseUrl
                azimuthOffset = preferences[UserPreferencesKeys.AZIMUTH_OFFSET] ?: azimuthOffset
                warehouseName = preferences[UserPreferencesKeys.WAREHOUSE_NAME] ?: warehouseName
                warehouseName = preferences[UserPreferencesKeys.WAREHOUSE_NAME] ?: warehouseName
                _serviceState.update { it.copy(useBleLocating = preferences[UserPreferencesKeys.USE_BLE_LOCATING] ?: _serviceState.value.useBleLocating) }
                _serviceState.update { it.copy(bluetoothTimeWindowSeconds = preferences[UserPreferencesKeys.BLUETOOTH_TIME_WINDOW_SECONDS] ?: _serviceState.value.bluetoothTimeWindowSeconds) }
            }
        }
        MqttClient.initialize(this, warehouseName = warehouseName, mqttServerUrl = mqttServerUrl, apiBaseUrl = apiBaseUrl)
        MqttClient.setCommandListener(this)
        deviceId = getDeviceId(applicationContext)

        // --- WiFi初始化 ---
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        // --- 蓝牙初始化 ---
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        // ... 注册WiFi Receiver ...
        registerReceiver(wifiScanReceiver, intentFilter)

//        timer = TimerUtils(
//            deviceId = deviceId,
//            coroutineScope = samplingServiceScope,
//            // Wi-Fi callbacks
//            getWiFiScanningResultCallback = {
//                wifiScanningResults.toList().also { wifiScanningResults.clear() }
//            },
//            clearWiFiScanningResultCallback = { wifiScanningResults.clear() },
//            wifiManagerScanning = { wifiManager.startScan() },
//            // 新增: Bluetooth callbacks
//            getBluetoothScanningResultCallback = {
//                // 1. 获取当前时间戳，作为时间窗口的结束点
//                val windowEndTime = System.currentTimeMillis()
//
//                // 2. 如果启用了时间窗口，计算窗口的起始点
//                val windowStartTime = if (_serviceState.value.isBluetoothTimeWindowEnabled) {
//                    windowEndTime - (_serviceState.value.bluetoothTimeWindowSeconds * 1000).toLong()
//                } else {
//                    0L // 如果未启用，则起始时间为0，意味着接受所有数据
//                }
//
//                // 3. 过滤当前缓冲区(bufferedBluetoothResults)中的数据
//                val filteredResults = bufferedBluetoothResults.filter { result ->
//                    // 将 ScanResult 的纳秒时间戳转换为毫秒
//                    val resultTimestamp = System.currentTimeMillis() - SystemClock.elapsedRealtime() + (result.timestampNanos / 1_000_000)
//                    // 只保留在 [windowStartTime, windowEndTime] 区间内的数据
//                    resultTimestamp in windowStartTime..windowEndTime
//                }
//
//                // 如果需要调试，可以打印过滤前后的数量
//                if (_serviceState.value.isBluetoothTimeWindowEnabled) {
//                    Log.d("BluetoothFilter", "Filtering: ${bufferedBluetoothResults.size} -> ${filteredResults.size} results within ${windowEndTime - windowStartTime}ms window.")
//                }
//
//                // 4. 使用过滤后的结果(filteredResults)来格式化并准备写入文件
//                val resultsToWrite = filteredResults.map { result ->
//                    // 使用最新的 Wi-Fi 时间戳进行格式化（这部分逻辑保持不变）
//                    "${this@FrontService.latestWifiTimestamp},${result.device.name ?: "N/A"},${result.device.address},2462,${result.rssi}\n"
//                }
//
//                // 5. 将当前主列表中的数据移入缓冲区，为下一个周期做准备 (这部分逻辑保持不变)
//                bufferedBluetoothResults = bluetoothScanningResults.toMutableList()
//
//                // 6. 清空主列表 (这部分逻辑保持不变)
//                bluetoothScanningResults.clear()
//
//                // 7. 返回格式化好的、将被写入文件的数据
//                resultsToWrite
//            },
//            clearBluetoothScanningResultCallback = { bluetoothScanningResults.clear() },
//            context = this@FrontService
//        )
//        motionSensorManager = SensorUtils(this@FrontService)
////        motionSensorManager.startMonitoring(this@FrontService)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        // 定义扫描设置
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // 高频率采集使用低延迟模式
            .build()
        // 开始扫描
        Log.d("BluetoothLifecycle", "Starting continuous Bluetooth scan.")
        bluetoothLeScanner.startScan(null, settings, leScanCallback)
    }

    // 新增一个方法来停止蓝牙扫描
    @SuppressLint("MissingPermission")
    private fun stopBluetoothScan() {
        bluetoothLeScanner.stopScan(leScanCallback)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onDestroy() {
        super.onDestroy()
        MqttClient.disconnect()
        stopLocating()
        stopCollectingData()
        motionSensorManager.stopMonitoring()
        unregisterReceiver(wifiScanReceiver)
        stopBluetoothScan()
        serviceJob.cancel()
        locatingServiceJob.cancel()
        isServiceRunning = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFrontService()
            ACTION_STOP -> stopFrontService()
        }
        return START_STICKY // If the service is killed, it will be automatically restarted.
    }

    private fun createNotification(): Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        val channelName = "Location Service"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to stop the service from the notification
        val stopIntent = Intent(this, FrontService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("WiMU Localization")
            .setContentText("Localization service is running.")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your app's icon
            .addAction(R.drawable.image_placeholder, "Stop", stopPendingIntent) // Replace with a stop icon
            .setOngoing(true)
            .build()
    }

    private fun startFrontService() {
        if (isServiceRunning) return
        startForeground(NOTIFICATION_ID, createNotification())
        isServiceRunning = true
    }

    private fun stopFrontService() {
        if (!isServiceRunning) return

        motionSensorManager.stopMonitoring()
        _serviceState.value = ServiceState() // Reset state
        serviceJob.cancelChildren() // Cancel all coroutines
        locatingServiceJob.cancelChildren()
        isServiceRunning = false
        stopForeground(true)
        stopSelf()
    }

    fun updateUseBleLocating(newValue: Boolean) {
        _serviceState.update { it.copy(useBleLocating = newValue) }
    }

    fun updateIsBluetoothTimeWindowEnabled(newValue: Boolean) {
        _serviceState.update { it.copy(isBluetoothTimeWindowEnabled = newValue) }
    }

    fun updateBluetoothTimeWindowSeconds(newValue: Float) {
        _serviceState.update { it.copy(bluetoothTimeWindowSeconds = newValue) }
    }

    fun updateWifiSamplingCycles(newValue: Float) {
        _serviceState.update { it.copy(wifiOrBleSamplingCycles = newValue) }
    }

    fun updateSensorSamplingCycles(newValue: Float) {
        _serviceState.update { it.copy(sensorSamplingCycles = newValue) }
    }

    fun updateSaveDirectory(newValue: String) {
        _serviceState.update { it.copy(saveDirectory = newValue) }
    }

    fun updateIsCollectTraining(newValue: Boolean) {
        _serviceState.update { it.copy(isCollectTraining = newValue) }
    }

    fun enableImuSensor() {
        _serviceState.update { it.copy(isImuEnabled = true) }
    }

    fun disableImuSensor() {
        _serviceState.update { it.copy(isImuEnabled = false) }
    }

    fun enableOwnStepCounter() {
        _serviceState.update { it.copy(isMyStepDetectorEnabled = true) }
    }

    fun disableOwnStepCounter() {
        _serviceState.update { it.copy(isMyStepDetectorEnabled = false) }
    }

    //业务逻辑层 (Business Logic Layers)：这就是“数据采集-保存”任务和“实时推理-上传”任务。它们是独立的、可以并行的 Job。它们不直接接触硬件，而是通过资源管理器来确保硬件已开启，并从全局共享缓冲区中读取数据。

    // --- 封装采集任务的上下文信息 ---
    private data class CollectionContext(
        val directory: File,
        val isLabeled: Boolean,
        val wifiWriter: FileWriter,
        val bleWriter: FileWriter,
        val imuWriter: FileWriter,
        val labelWriter: FileWriter? // 可空，因为只有Labeled采集才有
    )
    // --- 一个可空的上下文对象来代表采集状态 ---
    private var collectionContext: CollectionContext? = null

    // --- 统一的数据采集启动入口 ---
    @RequiresApi(Build.VERSION_CODES.O)
    fun startDataCollection(isLabeled: Boolean, labelPoint: Offset? = null, indexOfLabel: Int = 0) {
        // 防止重复启动
        if (collectionContext != null) {
            Log.w("DataCollection", "Data Collection is already active.")
            return
        }

        Log.i("DataCollection", "Starting Data Collection. Is Labeled: $isLabeled")
        _serviceState.update { it.copy(isSampling = true) }

        // 1. 申请硬件资源
        acquireResource(SensorResource.IMU)
        acquireResource(SensorResource.WIFI)
        acquireResource(SensorResource.BLUETOOTH)

        // 2. 准备目录和文件写入器 (在一个异步任务中完成)
        serviceScope.launch(Dispatchers.IO) {
            try {
                // 根据是否带标签，决定主目录和子目录名
                val mainDirName = if (isLabeled) "Train" else "Unlabeled"
                val startTimestamp = System.currentTimeMillis()
                val timestampStr = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(
                    Date(startTimestamp)
                )
                val subDirName = if (isLabeled) {
                    "Waypoint-${indexOfLabel + 1}-$timestampStr"
                } else {
                    "Trajectory-$timestampStr"
                }

                val mainDir = File(getExternalFilesDir(null), mainDirName)
                val collectionDir = File(mainDir, subDirName)
                if (!collectionDir.exists()) collectionDir.mkdirs()

                // 创建 Writers
                val wifiWriter = FileWriter(File(collectionDir, "wifi.csv"), true).apply { append("timestamp,ssid,bssid,frequency,level\n") }
                val bleWriter = FileWriter(File(collectionDir, "bluetooth.csv"), true).apply { append("timestamp,ssid,bssid,frequency,level,tx_power\n") }
                val imuWriter = FileWriter(File(collectionDir, "euler.csv"), true).apply { append("timestamp,dx,dy,steps\n") }
                var labelWriter: FileWriter? = null

                // 如果是 Labeled 模式，创建并写入 label.csv
                if (isLabeled && labelPoint != null) {
                    labelWriter = FileWriter(File(collectionDir, "label.csv"), true).apply {
                        append("waypoint_x,waypoint_y\n")
                        append("${labelPoint.x},${labelPoint.y}\n") // 一次性写入坐标
                        flush()
                    }
                }

                // 将所有信息保存到新的上下文中
                collectionContext = CollectionContext(
                    directory = collectionDir,
                    isLabeled = isLabeled,
                    wifiWriter = wifiWriter,
                    bleWriter = bleWriter,
                    imuWriter = imuWriter,
                    labelWriter = labelWriter
                )

                // 更新UI状态
                _serviceState.update {
                    it.copy(
                        isCollectTraining = isLabeled,
                        saveDirectory = subDirName,
                        numOfLabelSampling = if (isLabeled) indexOfLabel else null
                    )
                }

            } catch (e: IOException) {
                Log.e("DataCollection", "Failed to setup collection context", e)
                // 如果设置失败，确保状态被重置
                stopDataCollection()
            }
        }
    }

    fun startCollectingLabelData(indexOfLabel: Int, labelPoint: Offset, startTimestamp: Long) {
        if (_serviceState.value.isCollectTraining) {
            timer.setSavingDir("Train")
        } else {
            timer.setSavingDir("Test")
        }
        val currentTimeInText =
            SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(
                startTimestamp
            )
        val newSaveDirectory = "Waypoint-${indexOfLabel + 1}-$currentTimeInText"
        _serviceState.update {
            it.copy(
                numOfLabelSampling = indexOfLabel,
                saveDirectory = newSaveDirectory,
                isSampling = true
            )
        }
        samplingServiceScope.launch {
            timer.runSensorTaskAtFrequency(
                sensorManager = motionSensorManager,
                frequency = _serviceState.value.sensorSamplingCycles.toDouble(),
                timestamp = currentTimeInText,
                dirName = _serviceState.value.saveDirectory,
            )
        }
        samplingServiceScope.launch {
            timer.runScanningTaskAtFrequency(
                frequencyY = _serviceState.value.wifiOrBleSamplingCycles.toDouble(),
                timestamp = currentTimeInText,
                dirName = _serviceState.value.saveDirectory,
                collectWaypoint = true,
                waypointPosition = labelPoint
            )
        }
        motionSensorManager.startMonitoring(this@FrontService)
        startBluetoothScan()
    }

    fun startCollectingUnLabelData(startTimestamp: Long) {
        timer.setSavingDir("Unlabeled")
        val currentTimeInText =
            SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(
                startTimestamp
            )

        val newSaveDirectory = "Trajectory-$currentTimeInText"

        _serviceState.update {
            it.copy(
                saveDirectory = newSaveDirectory,
                isSampling = true
            )
        }

        samplingServiceScope.launch {
            timer.runSensorTaskAtFrequency(
                sensorManager = motionSensorManager,
                frequency = _serviceState.value.sensorSamplingCycles.toDouble(),
                timestamp = currentTimeInText,
                dirName = _serviceState.value.saveDirectory,
            )
        }
        samplingServiceScope.launch {
            timer.runScanningTaskAtFrequency(
                frequencyY = _serviceState.value.wifiOrBleSamplingCycles.toDouble(),
                timestamp = currentTimeInText,
                dirName = _serviceState.value.saveDirectory,
                collectWaypoint = false,
            )
        }
        motionSensorManager.startMonitoring(this@FrontService)
        startBluetoothScan()
        publishData("ack", data = AckData(deviceId = deviceId, ackInfo = "sample_on"))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun stopCollectingData() {
        Log.e("STOP SAMPLING", "HERE")
        _serviceState.update { it.copy(isSampling = false) }
        timer.stopTask(warehouseName = warehouseName, apiBaseUrl = apiBaseUrl)
        stopBluetoothScan()
        samplingServiceJob.cancelChildren()
        if (!_serviceState.value.isLocatingStarted && !_serviceState.value.isLoadingStarted) {
            motionSensorManager.stopMonitoring()
        }
        publishData("ack", data = AckData(deviceId = deviceId, ackInfo = "sample_off"))
    }

    // --- 推理模式枚举 ---
    enum class InferenceMode {
        NONE,           // 无推理模式
        BLUETOOTH,      // 蓝牙 + IMU 模式
        WIFI,           // Wi-Fi + IMU 模式
        WIFI_ROAD_NETWORK, // Wi-Fi + 路网模式
        WIFI_ONLY       // 纯 Wi-Fi 模式
    }
    // --- 推理模式的状态 ---
    private var currentInferenceMode = InferenceMode.NONE
    private var choseInferenceMode = InferenceMode.WIFI
    // --- 计步器，用于 Wi-Fi + 路网模式 ---
    private val roadNetworkStepCounter = AtomicInteger(0)

    @RequiresApi(Build.VERSION_CODES.O) // 因为可能调用stopDataCollection
    fun switchInferenceMode(newMode: InferenceMode) {
        // 如果模式没变，则什么都不做
        if (newMode == currentInferenceMode) return

        Log.i("InferenceEngine", "Switching inference mode from $currentInferenceMode to $newMode")

        // --- 1. 停止并清理旧模式 ---
        stopCurrentInferenceTasks()

        // --- 2. 更新当前模式状态 ---
        currentInferenceMode = newMode

        // --- 3. 根据新模式启动任务和申请资源 ---
        when (newMode) {
            InferenceMode.NONE -> {
                // 无需做任何事，因为旧模式已停止
            }
            InferenceMode.BLUETOOTH -> {
                Log.i("InferenceEngine", "Mode: BLUETOOTH. Acquiring resources and starting tasks.")
                // 申请资源
                acquireResource(SensorResource.IMU)
                acquireResource(SensorResource.BLUETOOTH)
                // TODO: 在这里启动蓝牙模式特有的任务（例如，一个固定周期的上传循环）
            }
            InferenceMode.WIFI -> {
                Log.i("InferenceEngine", "Mode: WIFI. Acquiring resources. Uploading will be event-driven.")
                // 申请资源
                acquireResource(SensorResource.IMU)
                acquireResource(SensorResource.WIFI)
                // 无需启动独立的Job，因为上传逻辑在Wi-Fi回调中
            }
            InferenceMode.WIFI_ROAD_NETWORK -> {
                Log.i("InferenceEngine", "Mode: WIFI_ROAD_NETWORK. Acquiring resources. Uploading will be event-driven.")
                // 申请资源
                acquireResource(SensorResource.IMU)
                acquireResource(SensorResource.WIFI)
                // 重置计步器
                roadNetworkStepCounter.set(0)
                // 上传逻辑在Wi-Fi回调和计步回调中
            }
            InferenceMode.WIFI_ONLY -> {
                Log.i("InferenceEngine", "Mode: WIFI_ONLY. Acquiring resources. Uploading will be event-driven.")
                // 只申请Wi-Fi资源
                acquireResource(SensorResource.WIFI)
            }
        }
    }

    // 用于 WIFI 模式的Wi-Fi数据上传
    private fun handleWifiModeUpload() {
        serviceScope.launch(Dispatchers.Default) {
            Log.d("InferenceEngine", "[WIFI Mode] Triggered by Wi-Fi scan result.")

            // 1. 收割IMU数据
            val displacement = imuDisplacementBuffer.getAndSet(Offset.Zero)
            val steps = stepCountBuffer.getAndSet(0)
            val imuData = ImuData(Pair(displacement.x, displacement.y), steps)

            // 2. 收割Wi-Fi数据
            val wifiData = wifiScanBuffer.toList()
            wifiScanBuffer.clear()

            // 3. 构建和发送数据包
            if (wifiData.isNotEmpty()) {
                val unifiedData = UnifiedSensorData(
                    deviceId = deviceId,
                    timestamp = System.currentTimeMillis(),
                    imu = imuData,
                    wifiScans = wifiData,
                    bleScans = null // 此模式不包含蓝牙
                )
                MqttClient.publishData("inference", unifiedData)
                Log.d("InferenceEngine", "[WIFI Mode] Published: $unifiedData")
            }
        }
    }

    // 用于 WIFI_ROAD_NETWORK 模式的 Wi-Fi 数据上传
    private fun handleRoadNetworkWifiUpload() {
        serviceScope.launch(Dispatchers.Default) {
            Log.d("InferenceEngine", "[ROAD_NETWORK Mode] Triggered by Wi-Fi scan result.")

            // 1. 只收割 Wi-Fi 数据
            val wifiData = wifiScanBuffer.toList()
            wifiScanBuffer.clear()

            // 2. 构建和发送只包含Wi-Fi的数据包
            if (wifiData.isNotEmpty()) {
                val unifiedData = UnifiedSensorData(
                    deviceId = deviceId,
                    timestamp = System.currentTimeMillis(),
                    imu = null,
                    wifiScans = wifiData,
                    bleScans = null
                )
                MqttClient.publishData("inference", unifiedData)
                Log.d("InferenceEngine", "[ROAD_NETWORK Mode] Published Wi-Fi data: $unifiedData")
            }
        }
    }

    // 用于 WIFI_ONLY 模式的 Wi-Fi 数据上传
    private fun handleWifiOnlyModeUpload() {
        serviceScope.launch(Dispatchers.Default) {
            Log.d("InferenceEngine", "[WIFI_ONLY Mode] Triggered by Wi-Fi scan result.")

            // 1. 收割Wi-Fi数据
            val wifiData = wifiScanBuffer.toList()
            wifiScanBuffer.clear()

            // 2. 构建和发送数据包（不包含IMU）
            if (wifiData.isNotEmpty()) {
                val unifiedData = UnifiedSensorData(
                    deviceId = deviceId,
                    timestamp = System.currentTimeMillis(),
                    imu = null, // 纯Wi-Fi模式没有IMU
                    wifiScans = wifiData,
                    bleScans = null
                )
                MqttClient.publishData("inference", unifiedData)
                Log.d("InferenceEngine", "[WIFI_ONLY Mode] Published: $unifiedData")
            }
        }
    }

    private fun handleRoadNetworkStepUpload() {
        serviceScope.launch(Dispatchers.Default) {
            // 重置计步器
            roadNetworkStepCounter.set(0)

            // 1. 只收割IMU数据
            // 注意：这里我们只取IMU在过去5步内的累积位移，这已由imuDisplacementBuffer自动完成。
            // getAndSet会原子性地获取当前值并重置为0，完美符合需求。
            val displacement = imuDisplacementBuffer.getAndSet(Offset.Zero)

            // 我们也需要过去5步的步数，这可以通过重置stepCountBuffer得到
            val steps = stepCountBuffer.getAndSet(0)

            Log.d("InferenceEngine", "[ROAD_NETWORK Mode] Triggered by 5 steps.")

            // 2. 构建和发送只包含IMU的数据包
            if (steps > 0) {
                val imuData = ImuData(Pair(displacement.x, displacement.y), steps)
                val unifiedData = UnifiedSensorData(
                    deviceId = deviceId,
                    timestamp = System.currentTimeMillis(),
                    imu = imuData,
                    wifiScans = null, // 此数据包不含Wi-Fi
                    bleScans = null
                )
                MqttClient.publishData("inference", unifiedData)
                Log.d("InferenceEngine", "[ROAD_NETWORK Mode] Published IMU data: $unifiedData")
            }
        }
    }

    private fun stopCurrentInferenceTasks() {
        Log.d("InferenceEngine", "Stopping tasks for mode: $currentInferenceMode")

        // 根据当前是什么模式，释放对应的资源
        when (currentInferenceMode) {
            InferenceMode.NONE -> { /* Do nothing */ }
            InferenceMode.BLUETOOTH -> {
                // TODO: 停止蓝牙模式的任何循环任务
                releaseResource(SensorResource.IMU)
                releaseResource(SensorResource.BLUETOOTH)
            }
            InferenceMode.WIFI -> {
                releaseResource(SensorResource.IMU)
                releaseResource(SensorResource.WIFI)
            }
            InferenceMode.WIFI_ROAD_NETWORK -> {
                releaseResource(SensorResource.IMU)
                releaseResource(SensorResource.WIFI)
            }
            InferenceMode.WIFI_ONLY -> {
                releaseResource(SensorResource.WIFI)
            }
        }
        // 将模式重置为NONE，确保状态一致
        currentInferenceMode = InferenceMode.NONE
    }

//    @SuppressLint("MissingPermission")
//    fun startLocating() {
//        _serviceState.update { it.copy(isLocatingStarted = true) }
//        motionSensorManager.startMonitoring(this@FrontService)
//        if (_serviceState.value.useBleLocating) {
//            startBluetoothScan()
//            locatingServiceScope.launch {
//                while (isServiceRunning) {
////                    val success = wifiManager.startScan()
//                    Log.d("BLE Inference Started", System.currentTimeMillis().toString())
//
////                if (success) {
////                    _serviceState.update { it.copy(isLoadingStarted = true) }
////                }
//                    // 1. 获取当前时间戳，作为时间窗口的结束点
//                    val windowEndTime = System.currentTimeMillis()
//
//                    // 2. 如果启用了时间窗口，计算窗口的起始点
//                    val windowStartTime = if (_serviceState.value.isBluetoothTimeWindowEnabled) {
//                        windowEndTime - (_serviceState.value.bluetoothTimeWindowSeconds * 1000).toLong()
//                    } else {
//                        0L // 如果未启用，则起始时间为0，意味着接受所有数据
//                    }
//
//                    // 3. 过滤当前缓冲区(bufferedBluetoothResults)中的数据
//                    val filteredBleResults = bufferedBluetoothResults.filter { result ->
//                        // 将 ScanResult 的纳秒时间戳转换为毫秒
//                        val resultTimestamp = System.currentTimeMillis() - SystemClock.elapsedRealtime() + (result.timestampNanos / 1_000_000)
//                        // 只保留在 [windowStartTime, windowEndTime] 区间内的数据
//                        resultTimestamp in windowStartTime..windowEndTime
//                    }
//
//                    // 如果需要调试，可以打印过滤前后的数量
//                    if (_serviceState.value.isBluetoothTimeWindowEnabled) {
//                        Log.d("BluetoothFilter_Infer", "Filtering: ${bufferedBluetoothResults.size} -> ${filteredBleResults.size} results within ${windowEndTime - windowStartTime}ms window.")
//                    }
//
//                    // 1. DATA TO WRITE NOW:
//                    //    Use the timestamp from the Wi-Fi scan that just finished
//                    //    to format the Bluetooth results from the PREVIOUS cycle, which are in the buffer.
//                    val directionSnapshot = bufferMutex.withLock {
//                        directionBuffer.toList().also {
//                            directionBuffer.clear()
//                        }
//                    }
//                    val displacement = calculateTotalDisplacement(directionSnapshot, stride)
//                    _serviceState.update { it.copy(imuOffset = displacement) }
//                    val resultsToWrite = filteredBleResults.map { result ->
//                        "${this@FrontService.latestWifiTimestamp},${result.device.name ?: "N/A"},${result.device.address},2462,${result.rssi}\n"
//                    }
//
//                    // 2. PREPARE FOR THE NEXT CYCLE:
//                    //    Move the results collected in the cycle that JUST ENDED (which are in the main list)
//                    //    into the buffer. They will wait there until the next Wi-Fi timestamp arrives.
//                    bufferedBluetoothResults = bluetoothScanningResults.toMutableList()
////                    Log.d("BLE", resultsToWrite.toString())
//                    publishData(
//                        topicSuffix = "inference_ble",
//                        data = MqttClient.InferenceData(
//                            deviceId = deviceId,
//                            wifiList = resultsToWrite,
//                            imuOffset = Pair(displacement.x, displacement.y),
//                            sysNoise = sysNoise,
//                            obsNoise = obsNoise,
//                            step = directionSnapshot.size,
//                            startTime = System.currentTimeMillis()
//                        )
//                    )
//                    // 3. CLEAR THE MAIN LIST:
//                    //    The main collection list is now empty, ready to start collecting new
//                    //    Bluetooth results for the upcoming cycle.
//                    bluetoothScanningResults.clear()
//
//                    // 4. RETURN THE DATA TO BE WRITTEN:
//                    //    Send the formatted data from the previous cycle to TimerUtils.
//                    delay((1 * 1000).toLong())
//                }
//            }
//            publishData("ack", data = AckData(deviceId = deviceId, ackInfo = "inference_on"))
//        } else {
//
//            locatingServiceScope.launch {
//                while (isServiceRunning) {
//                    val success = wifiManager.startScan()
//                    Log.d("StartLocating", "Triggered")
//                    delay((period * 1000).toLong())
//                }
//            }
//            publishData("ack", data = AckData(deviceId = deviceId, ackInfo = "inference_on"))
//        }
//    }

    // UI或远程命令将调用这个方法来改变模式
    @RequiresApi(Build.VERSION_CODES.O)
    fun setInferenceMode(mode: InferenceMode) {
        switchInferenceMode(mode)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startLocating() {
        // 使用选择的inference模式
        _serviceState.update { it.copy(isLocatingStarted = (choseInferenceMode != InferenceMode.NONE)) }
        setInferenceMode(choseInferenceMode)
    }

    fun refreshLocating() {
        //NOTHING
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun stopLocating() {
        if (!isServiceRunning) return
        _serviceState.update {
            it.copy(
                isLoadingStarted = false,
                isLocatingStarted = false,
                targetOffset = Offset(0f, 0f)
            )
        }
        setInferenceMode(InferenceMode.NONE)
        publishData("ack", data = AckData(deviceId = deviceId, ackInfo = "inference_off"))
    }

    private fun onLatestWifiResultChanged(newValue: List<String>) {
        try {
            serviceScope.launch {
                if (!firstStart) {
                    val directionSnapshot = bufferMutex.withLock {
                        directionBuffer.toList().also {
                            directionBuffer.clear()
                        }
                    }
                    val displacement = calculateTotalDisplacement(directionSnapshot, stride)
                    _serviceState.update { it.copy(uploadOffset = displacement) }
                    publishData(
                        topicSuffix = "inference_wifi",
                        data = MqttClient.InferenceData(
                            deviceId = deviceId,
                            wifiList = newValue,
//                            imuOffset = Pair(displacement.x, displacement.y),
                            imuOffset = null, // TODO: use mengxuan's particle filter
                            sysNoise = sysNoise,
                            obsNoise = obsNoise,
                            step = directionSnapshot.size,
                            startTime = System.currentTimeMillis()
                        )
                    )
                } else {
                    publishData(
                        topicSuffix = "inference_wifi",
                        data = MqttClient.InferenceData(
                            deviceId = deviceId,
                            wifiList = newValue,
                            imuOffset = null,
                            sysNoise = sysNoise,
                            obsNoise = obsNoise,
                            step = 0,
                            startTime = System.currentTimeMillis()
                        )
                    )
                }
            }
            this.imuOffsetHistory.clear()
            this.distFromLastPos = 0f
            this.firstStart = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onRotationVectorChanged(rotationVector: FloatArray) {
        lastRotationVector = rotationVector
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
        val orientations = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientations)
        val newYaw = Math.toDegrees(orientations[0].toDouble()).toFloat() + azimuthOffset // TODO: save
        val newPitch = Math.toDegrees(orientations[1].toDouble()).toFloat()
        val newRoll = Math.toDegrees(orientations[2].toDouble()).toFloat()
        _serviceState.update { it.copy(yaw = newYaw, pitch = newPitch, roll = newRoll, userHeading = newYaw) }
    }

    override fun onSingleStepChanged() {
        // 通用逻辑：所有模式下都累积位移和步数
        stepCountBuffer.incrementAndGet() // 步数+1
        // 计算当前步的位移增量
        val azimuth = Math.toRadians(_serviceState.value.yaw.toDouble()).toFloat()
        val dx = -stride * cos(azimuth)
        val dy = -stride * sin(azimuth)

        // 原子地更新位移累加值
        imuDisplacementBuffer.getAndUpdate { currentDisplacement ->
            Offset(currentDisplacement.x + dx, currentDisplacement.y + dy)
        }

        // --- 特定模式逻辑：Wi-Fi + 路网模式 ---
        if (currentInferenceMode == InferenceMode.WIFI_ROAD_NETWORK) {
            // 原子地增加步数，如果达到5步，则触发上传
            if (roadNetworkStepCounter.incrementAndGet() >= 5) {
                handleRoadNetworkStepUpload()
            }
        }
    }

    override fun onMyStepChanged() {
        if (!_serviceState.value.isMyStepDetectorEnabled || !isServiceRunning) {
            return // 如果相关功能未开启，则直接返回，不进行任何处理
        }

        stepCountBuffer.incrementAndGet() // 步数+1

        // 计算当前步的位移增量
        val azimuth = Math.toRadians(_serviceState.value.yaw.toDouble()).toFloat()
        val dx = -stride * cos(azimuth)
        val dy = -stride * sin(azimuth)

        // 原子地更新位移累加值
        imuDisplacementBuffer.getAndUpdate { currentDisplacement ->
            Offset(currentDisplacement.x + dx, currentDisplacement.y + dy)
        }
//        if (_serviceState.value.isMyStepDetectorEnabled) {
//            stepCountHistory += 1
//            val azimuth = Math.toRadians(_serviceState.value.yaw.toDouble()).toFloat()
//            val dx = -stride * cos(azimuth)
//            val dy = -stride * sin(azimuth)
//            distFromLastPos += stride
//            // TODO: currently disable gesture check
//            if (isServiceRunning && _serviceState.value.isImuEnabled) {
//                serviceScope.launch {
//                    bufferMutex.withLock {
//                        directionBuffer.add(azimuth)
//                    }
//                }
//                _serviceState.update {
//                    it.copy(
////                        targetOffset = Offset(
////                            _serviceState.value.targetOffset.x + dx,
////                            _serviceState.value.targetOffset.y + dy
////                        ),
//                        imuOffset = Offset(
//                            _serviceState.value.imuOffset.x + dx,
//                            _serviceState.value.imuOffset.y + dy
//                        ),
//                        cumulatedStep = _serviceState.value.cumulatedStep + 1
//                    )
//                }
//                // TODO: Only open when mengxuan's road map is enabled
//                if (_serviceState.value.isLocatingStarted) {
//                    if (lastStepCountFromMyStepDetector % 5 == 0) {
//                        val targetTopic = if (_serviceState.value.useBleLocating) {
//                            "inference_ble"
//                        } else {
//                            "inference_wifi"
//                        }
//                        publishData(
//                            targetTopic,
//                            MqttClient.InferenceData(
//                                deviceId = deviceId,
//                                wifiList = null,
//                                imuOffset = Pair(displacement.x, displacement.y),
//                                obsNoise = obsNoise,
//                                sysNoise = sysNoise,
//                                step = lastStepCountFromMyStepDetector,
//                                startTime = System.currentTimeMillis()
//                            )
//                        )
//                        displacement = Offset.Zero
//                    } else {
//                        displacement = Offset(displacement.x + dx, displacement.y + dy)
//                    }
//                }
//            }
//        }
    }

    override fun onStepCountChanged(stepCount: Float) {
        lastStepCount = stepCount
    }

    override fun onMagChanged(mag: FloatArray) {
        lastGeomagnetic = lowPassFilter(mag.copyOf(), lastGeomagnetic) // You need to add `lowPassFilter` function
        val x = mag[0]
        val y = mag[1]
        val z = mag[2]
        lastMag = sqrt(x * x + y * y + z * z)
    }

    override fun onAccChanged(acc: FloatArray) {
        lastGravity = lowPassFilter(acc.copyOf(), lastGravity) // You need to add `lowPassFilter`
//        accX = acc[0]
//        accY = acc[1]
//        accZ = acc[2]
//        val acceleration = sqrt(
//            acc[0].pow(2) +
//                    acc[1].pow(2) +
//                    acc[2].pow(2)
//        ) - SensorManager.GRAVITY_EARTH
//
//        if (acceleration > 2.0f) { // 检测加速度峰值（步伐特征）
//            val currentTime = System.currentTimeMillis()
//            val lastStepTime = this.motionSensorManager.getLastSingleStepTime()
//            if (lastStepTime != null && currentTime - lastStepTime > 300) { // 过滤间隔小于300ms的误检
//                estimatedStrideLength = userHeight * strideCoefficient
//            }
//        }
    }

    override fun updateOrientation() {
        // 获取设备方向（弧度）
        val orientationAngles = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        // 将加速度从设备坐标系转换到地球坐标系
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            rotationMatrix
        )
        val aEarth = FloatArray(3)
//        SensorManager.rotateVector(aEarth, rotationMatrix, accelerometerData)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        // 转换加速度到地球坐标系
        val accelDevice = lastGravity.clone()
        val accelWorld = FloatArray(3)
        for (i in 0..2) {
            accelWorld[i] = rotationMatrix[i] * accelDevice[0] +
                    rotationMatrix[i + 3] * accelDevice[1] +
                    rotationMatrix[i + 6] * accelDevice[2]
        }

        // 提取水平方向（忽略垂直轴）
        val horizontalX = accelWorld[0]
        val horizontalY = accelWorld[1]

        // 计算前进方向（弧度）
        val direction = Math.atan2(horizontalY.toDouble(), horizontalX.toDouble()).toFloat()

        // 低通滤波
        filteredDirection += alpha * (direction - filteredDirection)

        // 转换为角度（0-360度）
        adjustedDegrees = Math.toDegrees(filteredDirection.toDouble()).toFloat()
//        val angleFromNorth = ((90 - Math.toDegrees(angleFromEast)) % 360).toFloat()
//        adjustedDegrees = if (angleFromNorth > 180) angleFromNorth - 360 else angleFromNorth
//        adjustedDegrees = (degrees + 360) % 360
    }

    override fun onStartSampling() {
        val timestamp = System.currentTimeMillis()
        startCollectingUnLabelData(timestamp) //TODO: "Maybe support labeled sampling?"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStopSampling() {
        stopCollectingData()
    }

    override fun onStartInference() {
        Log.d("DEBUG", "Triggered")
        startLocating()
    }

    override fun onStopInference() {
        Log.d("DEBUG", "STOP TRIGGERED")
        stopLocating()
    }

    override fun onGetInferenceResult(x: Float, y: Float, rtt: Float) {
        Log.e("TARGET OFFSET", "$x, $y")
        this.lastOffset = Offset(x, y)
        _serviceState.update {
            it.copy(
                targetOffset = Offset(x, y)
            )
        }
        _serviceState.update { it.copy(
            isLoadingStarted = true,
            rttLatency = rtt
        ) }
    }

    override fun onUnknownCommand(command: String) {
        TODO("Not yet implemented")
    }

    override fun onCommandError(payload: String, error: Throwable) {
        TODO("Not yet implemented")
    }
}