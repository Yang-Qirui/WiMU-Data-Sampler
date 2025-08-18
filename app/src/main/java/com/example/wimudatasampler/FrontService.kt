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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.core.app.NotificationCompat
import com.example.wimudatasampler.Config.API_BASE_URL
import com.example.wimudatasampler.Config.MQTT_SERVER_URI
import com.example.wimudatasampler.network.MqttClient
import com.example.wimudatasampler.network.MqttClient.AckData
import com.example.wimudatasampler.network.MqttClient.publishData
import com.example.wimudatasampler.utils.CoroutineLockIndexedList
import com.example.wimudatasampler.utils.calculateTotalDisplacement
import com.example.wimudatasampler.utils.MqttCommandListener
import com.example.wimudatasampler.utils.Quadruple
import com.example.wimudatasampler.utils.SensorUtils
import com.example.wimudatasampler.utils.TimerUtils
import com.example.wimudatasampler.utils.UserPreferencesKeys
import com.example.wimudatasampler.utils.lowPassFilter
import com.example.wimudatasampler.utils.validPostureCheck
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class ServiceState(
    //UI State
    var isReRegistering: Boolean = false, // <--- 新增这一行
    var isCollectTraining: Boolean = false,
    var isSampling: Boolean = false,
    var isLocatingStarted: Boolean = false,
    var isLoadingStarted: Boolean = false,
    var isImuEnabled: Boolean = true,
    var isMyStepDetectorEnabled: Boolean = false, //TODO: use own step detector
    //Sampling Page Data
    val yaw: Float = 0.0F,
    val pitch: Float = 0.0F,
    val roll: Float = 0.0F,
    var numOfLabelSampling: Int? = null, // Start from 0
    var wifiScanningInfo: String? = null,
    var wifiSamplingCycles: Float = 3.0F,
    var sensorSamplingCycles: Float = 0.05F,
    var saveDirectory: String = "",
    var targetOffset: Offset = Offset.Zero, // User's physical location
    val userHeading: Float? = null, // User orientation Angle (0-360)
    val waypoints: SnapshotStateList<Offset> = mutableStateListOf(),
    var imuOffset: Offset? = null,
    //此处可添加更多需要暴露给软件UI的值
    //...
)

class FrontService : Service(), SensorUtils.SensorDataListener, MqttCommandListener {
    private lateinit var motionSensorManager: SensorUtils
    lateinit var wifiManager: WifiManager
    // 蓝牙相关
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: android.bluetooth.le.BluetoothLeScanner
    private lateinit var wifiScanReceiver: BroadcastReceiver
    private lateinit var timer: TimerUtils
    private lateinit var deviceId: String

    // Coroutine Scope for the service
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val samplingServiceJob = Job()
    private val samplingServiceScope = CoroutineScope(Dispatchers.IO + samplingServiceJob)
    private val locatingServiceJob = Job()
    private val locatingServiceScope = CoroutineScope(Dispatchers.IO + locatingServiceJob)

    // StateFlow to communicate with the UI (Activity)
    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState = _serviceState.asStateFlow()

    // --- variables hidden from UI level ---
    private var wifiOffset by mutableStateOf<Offset?>(null)
    private var lastRotationVector: FloatArray? = null
    private var rotationMatrix = FloatArray(9)
    private var lastStepCount: Float? = null
    private var lastStepCountFromMyStepDetector = 0f
    private var lastGravity = FloatArray(3)
    private var lastGeomagnetic = FloatArray(3)
    private var lastMag by mutableFloatStateOf(0f)
    private var wifiScanningResults = mutableListOf<String>()
    // 蓝牙扫描结果列表
    private var bluetoothScanningResults = mutableListOf<ScanResult>()

    private var bufferedBluetoothResults = mutableListOf<ScanResult>()
    // --- A variable to hold the latest Wi-Fi timestamp ---
    private var latestWifiTimestamp: Long = 0L

    private var imuOffsetHistory = CoroutineLockIndexedList<Offset, Int>()
    private var stepCountHistory = 0
    private var lastOffset = Offset.Zero
    private var firstStart = true
    private var adjustedDegrees by mutableFloatStateOf(0f)

    private var alpha = 0.1f
    private var filteredDirection = 0f

    // 持久化的变量
    var stride by mutableFloatStateOf(0.55f)
    var beta by mutableFloatStateOf(0.9f)
    var estimatedStrideLength by mutableFloatStateOf(0f)
    var estimatedStrides = mutableListOf<Float>()
    private val directionBuffer = CopyOnWriteArrayList<Float>()
    private val bufferMutex = Mutex()

    var sysNoise = 1f
    var obsNoise = 3f
    var distFromLastPos = 0f

    var period = 3f

    var url = "http://limcpu1.cse.ust.hk:7860"
    var mqttServerUrl = MQTT_SERVER_URI
    var apiBaseUrl = API_BASE_URL
    var azimuthOffset = 90f
    var warehouseName = "jd-langfang"
    // 持久化的变量

    companion object {
        const val ACTION_START = "com.example.wimudatasampler.action.START"
        const val ACTION_STOP = "com.example.wimudatasampler.action.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "LocationServiceChannel"
        private const val NOTIFICATION_ID = 1
        var isServiceRunning = false
    }

    private val binder = LocationBinder()

    inner class LocationBinder : Binder() {
        fun getService(): FrontService = this@FrontService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // 蓝牙扫描回调
    @SuppressLint("MissingPermission") // 确保已在Manifest声明并在运行时请求
    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let {
                val timestamp = System.currentTimeMillis() - SystemClock.elapsedRealtime() + (it.timestampNanos / 1_000_000)
                // 格式化蓝牙扫描结果
                val formattedResult = "$latestWifiTimestamp,${it.device.name ?: "N/A"},${it.device.address},${it.rssi}\n"
                bluetoothScanningResults.add(it)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { result ->
                val timestamp = System.currentTimeMillis() - SystemClock.elapsedRealtime() + (result.timestampNanos / 1_000_000)
                val formattedResult = "$latestWifiTimestamp,${result.device.name ?: "N/A"},${result.device.address},${result.rssi}\n"
                bluetoothScanningResults.add(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BluetoothScan", "Scan Failed with error code: $errorCode")

            // Best Practice: Attempt to restart scan on certain failures
            if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                // This can sometimes be fixed by stopping and starting again after a short delay
                stopBluetoothScan()
                serviceScope.launch {
                    delay(200) // a short delay
                    startBluetoothScan()
                }
            }
        }
    }

    // 新增：用于触发重新注册的公共方法
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
                // 在加载完URL后，初始化 MqttClient
//                MqttClient.initialize(this@FrontService, mqttServerUrl = mqttServerUrl, apiBaseUrl = apiBaseUrl)
//                MqttClient.setCommandListener(this@FrontService)
            }
        }
        MqttClient.initialize(this, warehouseName = warehouseName, mqttServerUrl = mqttServerUrl, apiBaseUrl = apiBaseUrl)
        MqttClient.setCommandListener(this)
        deviceId = getDeviceId(applicationContext)
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiScanReceiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    val scanResults = wifiManager.scanResults
                    if (scanResults.isNotEmpty()) {
                        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                        val minScanTime = scanResults.minOf { it.timestamp }
                        val maxScanTime = scanResults.maxOf { it.timestamp }
                        Log.d("Diff", "${maxScanTime - minScanTime}")
                        Log.d("RECEIVED_RES", scanResults.toString())
                        // --- MODIFICATION END ---
                        if (maxScanTime - minScanTime < 500_000_000) {
                            val wifiTimestamp = (minScanTime / 1000 + bootTime)
                            this@FrontService.latestWifiTimestamp = wifiTimestamp
                            wifiScanningResults = scanResults.map { scanResult ->
                                "${wifiTimestamp},${scanResult.SSID},${scanResult.BSSID},${scanResult.frequency},${scanResult.level}\n"
                            }.toMutableList()
//                            if (_serviceState.value.isLocatingStarted) {
//                                locatingServiceScope.launch { onLatestWifiResultChanged(wifiScanningResults.toList()) } //TODO: changed to bluetooth
//                            }
                            _serviceState.update { it.copy(wifiScanningInfo = timer.wifiScanningInfo) }
                        }
                    } else {
                        Log.e("RECEIVED", "No Wi-Fi scan results found")
                    }
                }
            }
        }
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        // --- 新增: 蓝牙初始化 ---
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        registerReceiver(wifiScanReceiver, intentFilter)

        timer = TimerUtils(
            deviceId = deviceId,
            coroutineScope = samplingServiceScope,
            // Wi-Fi callbacks
            getWiFiScanningResultCallback = { wifiScanningResults.toList().also { wifiScanningResults.clear() } },
            clearWiFiScanningResultCallback = { wifiScanningResults.clear() },
            wifiManagerScanning = { wifiManager.startScan() },
            // 新增: Bluetooth callbacks
            getBluetoothScanningResultCallback = {
                // 1. DATA TO WRITE NOW:
                //    Use the timestamp from the Wi-Fi scan that just finished
                //    to format the Bluetooth results from the PREVIOUS cycle, which are in the buffer.
                val resultsToWrite = bufferedBluetoothResults.map { result ->
                    "${this@FrontService.latestWifiTimestamp},${result.device.name ?: "N/A"},${result.device.address},2462,${result.rssi}\n"
                }

                // 2. PREPARE FOR THE NEXT CYCLE:
                //    Move the results collected in the cycle that JUST ENDED (which are in the main list)
                //    into the buffer. They will wait there until the next Wi-Fi timestamp arrives.
                bufferedBluetoothResults = bluetoothScanningResults.toMutableList()

                // 3. CLEAR THE MAIN LIST:
                //    The main collection list is now empty, ready to start collecting new
                //    Bluetooth results for the upcoming cycle.
                bluetoothScanningResults.clear()

                // 4. RETURN THE DATA TO BE WRITTEN:
                //    Send the formatted data from the previous cycle to TimerUtils.
                resultsToWrite},
            clearBluetoothScanningResultCallback = { bluetoothScanningResults.clear() },
            context = this@FrontService
        )
        motionSensorManager = SensorUtils(this@FrontService)
//        motionSensorManager.startMonitoring(this@FrontService)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        // 每次重新开始扫描前，不清除上一次的结果
        // bluetoothScanningResults.clear()
        // 定义扫描设置
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // 高频率采集使用低延迟模式
            .build()
        // 开始扫描
        Log.d("BluetoothLifecycle", "Starting continuous Bluetooth scan.")
        bluetoothLeScanner.startScan(null, settings, leScanCallback)
        // 为了和Wi-Fi的周期对齐，我们让它扫描一小段时间然后停止，等待下一个周期的触发
        // TimerUtils中的delay会控制整体频率，我们只需要确保每次触发时都能扫到新的设备
        // 一个常见的模式是短时扫描，或者持续扫描并在TimerUtils中获取快照
        // 这里我们采用触发-扫描-获取-清除的模式，所以每次都重新startScan
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
        samplingServiceJob.cancel()
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
        imuOffsetHistory.clear()
        stepCountHistory = 0
        firstStart = true
        _serviceState.value = ServiceState() // Reset state

        // Log final data if needed
        for (item in estimatedStrides) {
            Log.d("Stride", item.toString())
        }

        serviceJob.cancelChildren() // Cancel all coroutines
        samplingServiceJob.cancelChildren()
        locatingServiceJob.cancelChildren()
        isServiceRunning = false
        stopForeground(true)
        stopSelf()
    }

    fun updateWifiSamplingCycles(newValue: Float) {
        _serviceState.update { it.copy(wifiSamplingCycles = newValue) }
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
                frequencyY = _serviceState.value.wifiSamplingCycles.toDouble(),
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
                frequencyY = _serviceState.value.wifiSamplingCycles.toDouble(),
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

    @SuppressLint("MissingPermission")
    fun startLocating() {
        _serviceState.update { it.copy(isLocatingStarted = true) }
        motionSensorManager.startMonitoring(this@FrontService)
        startBluetoothScan()
        locatingServiceScope.launch {
            while (isServiceRunning) {
                val success = wifiManager.startScan()
                Log.d("StartLocating", "Triggered")
//                if (success) {
//                    _serviceState.update { it.copy(isLoadingStarted = true) }
//                }
                // 1. DATA TO WRITE NOW:
                //    Use the timestamp from the Wi-Fi scan that just finished
                //    to format the Bluetooth results from the PREVIOUS cycle, which are in the buffer.
                val directionSnapshot = bufferMutex.withLock {
                    directionBuffer.toList().also {
                        directionBuffer.clear()
                    }
                }
                val displacement = calculateTotalDisplacement(directionSnapshot, stride)
                _serviceState.update { it.copy(imuOffset = displacement) }
                val resultsToWrite = bufferedBluetoothResults.map { result ->
                    "${this@FrontService.latestWifiTimestamp},${result.device.name ?: "N/A"},${result.device.address},2462,${result.rssi}\n"
                }

                // 2. PREPARE FOR THE NEXT CYCLE:
                //    Move the results collected in the cycle that JUST ENDED (which are in the main list)
                //    into the buffer. They will wait there until the next Wi-Fi timestamp arrives.
                bufferedBluetoothResults = bluetoothScanningResults.toMutableList()
                Log.d("BLE", resultsToWrite.toString())
                publishData(
                    topicSuffix = "inference",
                    data = MqttClient.InferenceData(
                        deviceId = deviceId,
                        wifiList = resultsToWrite,
                        imuOffset = Pair(displacement.x, displacement.y),
                        sysNoise = sysNoise,
                        obsNoise = obsNoise,
                    )
                )
                // 3. CLEAR THE MAIN LIST:
                //    The main collection list is now empty, ready to start collecting new
                //    Bluetooth results for the upcoming cycle.
                bluetoothScanningResults.clear()

                // 4. RETURN THE DATA TO BE WRITTEN:
                //    Send the formatted data from the previous cycle to TimerUtils.
                delay((period * 1000).toLong())
            }
        }
        publishData("ack", data = AckData(deviceId = deviceId, ackInfo = "inference_on"))
    }

    fun refreshLocating() {
        if (wifiOffset != null) {
            _serviceState.update { it.copy(targetOffset = wifiOffset!!) }
        }
    }

    fun stopLocating() {
        if (!isServiceRunning) return
        locatingServiceJob.cancelChildren()
        if (!_serviceState.value.isSampling) {
            motionSensorManager.stopMonitoring()
        }
        Log.d("DEBUG", "TRIGGER_STOP_LOC")
        _serviceState.update {
            it.copy(
                isLoadingStarted = false,
                isLocatingStarted = false,
                targetOffset = Offset(0f, 0f)
            )
        }
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
                    _serviceState.update { it.copy(imuOffset = displacement) }
                    publishData(
                        topicSuffix = "inference",
                        data = MqttClient.InferenceData(
                            deviceId = deviceId,
                            wifiList = newValue,
                            imuOffset = Pair(displacement.x, displacement.y),
                            sysNoise = sysNoise,
                            obsNoise = obsNoise,
                        )
                    )
                } else {
                    publishData(
                        topicSuffix = "inference",
                        data = MqttClient.InferenceData(
                            deviceId = deviceId,
                            wifiList = newValue,
                            imuOffset = null,
                            sysNoise = sysNoise,
                            obsNoise = obsNoise,
                        )
                    )
                }
            }
//            Log.d("current pos", response.bodyAsText())
//            val coordinate = Json.decodeFromString<Coordinate>(response.bodyAsText())
//            if (!firstStart && latestTimestamp != null && latestTimestamp - latestStepCount != 0) {
//                val delta = sqrt((inputImuOffset.x + lastOffset.x - coordinate.x).pow(2) + (inputImuOffset.y + lastOffset.y - coordinate.y).pow(2))
//                Log.d("Delta", "$delta, ${distFromLastPos + delta}")
//
//                val estimatedStride = (distFromLastPos + delta) / (latestTimestamp - latestStepCount)
//                Log.d("Est", estimatedStride.toString())
//                Log.d("Step diff", "${latestTimestamp - latestStepCount}")
//                latestStepCount = latestTimestamp
//                if (0.45 < estimatedStride && estimatedStride < 0.6) {
//                    stride = (1 - beta) * stride + beta * estimatedStride
//                }
//                estimatedStrides.add(estimatedStride)
//            }
//            Log.e("TARGET OFFSET", "${coordinate.x}, ${coordinate.y}")
//            _serviceState.value.targetOffset = Offset(coordinate.x, coordinate.y)
//            this.lastOffset = Offset(coordinate.x, coordinate.y)
//            _serviceState.value.imuOffset = Offset(0f, 0f)
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
        val newYaw = Math.toDegrees(orientations[0].toDouble()).toFloat() + azimuthOffset
        val newPitch = Math.toDegrees(orientations[1].toDouble()).toFloat()
        val newRoll = Math.toDegrees(orientations[2].toDouble()).toFloat()
        _serviceState.update { it.copy(yaw = newYaw, pitch = newPitch, roll = newRoll, userHeading = newYaw) }
//        val recentInvalidCount = eulerHistory.checkAll()
//        enableImu = if (recentInvalidCount / eulerHistory.getSize() > 0.6) {
//            false
//        } else {
//            true
//        }
    }

    override fun onSingleStepChanged() {
        if (!_serviceState.value.isMyStepDetectorEnabled) {
            stepCountHistory += 1
//            Log.d("STEP COUNT", "$stepCountHistory, $stepCount, $lastStepCountFromMyStepDetector")
            if (_serviceState.value.imuOffset != null) {
                val currentYaw = _serviceState.value.yaw
                val azimuth = Math.toRadians(currentYaw.toDouble()).toFloat()
                val dx = -stride * cos(azimuth)
                val dy = -stride * sin(azimuth)
                distFromLastPos += stride

//                if (isServiceRunning && _serviceState.value.isImuEnabled && validPostureCheck(
//                        _serviceState.value.pitch,
//                        _serviceState.value.roll
//                    )
//                ) {
//                    imuOffsetHistory.put(
//                        Quadruple(
//                            System.currentTimeMillis(),
//                            _serviceState.value.imuOffset!!,
//                            stepCountHistory,
//                            validPostureCheck(_serviceState.value.pitch, _serviceState.value.roll)
//                        )
//                    )
//
//                }
                if (isServiceRunning && _serviceState.value.isImuEnabled) {
                    serviceScope.launch {
                        bufferMutex.withLock {
                            directionBuffer.add(azimuth)
                        }
                    }
                    _serviceState.update {
                        it.copy(
                            targetOffset = Offset(
                                _serviceState.value.targetOffset.x + dx,
                                _serviceState.value.targetOffset.y + dy
                            )
                        )
                    }
                }
            }
        }
    }

    override fun onMyStepChanged() {
        lastStepCountFromMyStepDetector += 1
        if (_serviceState.value.isMyStepDetectorEnabled) {
            stepCountHistory += 1
            if (_serviceState.value.imuOffset != null) {
                val azimuth = Math.toRadians(_serviceState.value.yaw.toDouble()).toFloat()
                val dx = -stride * cos(azimuth)
                val dy = -stride * sin(azimuth)
                distFromLastPos += stride
//                if (isServiceRunning && _serviceState.value.isMyStepDetectorEnabled && validPostureCheck(_serviceState.value.pitch, _serviceState.value.roll)) {
//                    imuOffsetHistory.put(
//                        Quadruple(
//                            System.currentTimeMillis(),
//                            _serviceState.value.imuOffset!!,
//                            stepCountHistory,
//                            validPostureCheck(_serviceState.value.pitch, _serviceState.value.roll)
//                        )
//                    )
//                    _serviceState.update {
//                        it.copy(
//                            targetOffset = Offset(_serviceState.value.targetOffset.x + dx, _serviceState.value.targetOffset.y + dy)
//                        )
//                    }
//                }
                // TODO: currently disable gesture check
                if (isServiceRunning && _serviceState.value.isImuEnabled) {
                    serviceScope.launch {
                        bufferMutex.withLock {
                            directionBuffer.add(azimuth)
                        }
                    }
                    _serviceState.update {
                        it.copy(
                            targetOffset = Offset(
                                _serviceState.value.targetOffset.x + dx,
                                _serviceState.value.targetOffset.y + dy
                            )
                        )
                    }
                }
            }
        }
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

    override fun onGetInferenceResult(x: Float, y: Float) {
        Log.e("TARGET OFFSET", "$x, $y")
        this.lastOffset = Offset(x, y)
        _serviceState.update {
            it.copy(
                targetOffset = Offset(x, y)
            )
        }
        _serviceState.update { it.copy(isLoadingStarted = true) }
        if (_serviceState.value.imuOffset == null) {
            _serviceState.update {
                it.copy(
                    imuOffset = Offset(0f, 0f)
                )
            }
        }
    }

    override fun onUnknownCommand(command: String) {
        TODO("Not yet implemented")
    }

    override fun onCommandError(payload: String, error: Throwable) {
        TODO("Not yet implemented")
    }
}