package com.example.wimudatasampler

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

    var period = 5f

    var url = "http://limcpu1.cse.ust.hk:7860"
    var mqttServerUrl = MQTT_SERVER_URI
    var apiBaseUrl = API_BASE_URL
    var azimuthOffset = 90f
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

    @RequiresApi(Build.VERSION_CODES.N_MR1)
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
            }
        }
        MqttClient.initialize(this, mqttServerUrl = mqttServerUrl, apiBaseUrl = apiBaseUrl)
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
                        if (maxScanTime - minScanTime < 500_000_000) {
                            wifiScanningResults = scanResults.map { scanResult ->
                                "${(minScanTime / 1000 + bootTime)} ${scanResult.SSID} ${scanResult.BSSID} ${scanResult.frequency} ${scanResult.level}\n"
                            }.toMutableList()
                            if (_serviceState.value.isLocatingStarted) {
                                locatingServiceScope.launch { onLatestWifiResultChanged(wifiScanningResults.toList()) }
                            }
                            _serviceState.update { it.copy(wifiScanningInfo = timer.wifiScanningInfo) }
                        }
                    } else {
                        Log.e("RECEIVED", "No Wi-Fi scan results found")
                    }
                }
            }
        }
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        timer = TimerUtils(deviceId, samplingServiceScope, {wifiScanningResults}, {wifiScanningResults.clear()}, { wifiManager.startScan() }, this@FrontService)
        motionSensorManager = SensorUtils(this@FrontService)
//        motionSensorManager.startMonitoring(this@FrontService)
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttClient.disconnect()
        stopLocating()
        stopCollectingData()
        motionSensorManager.stopMonitoring()
        unregisterReceiver(wifiScanReceiver)
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
            timer.runWifiTaskAtFrequency(
                frequencyY = _serviceState.value.wifiSamplingCycles.toDouble(),
                timestamp = currentTimeInText,
                dirName = _serviceState.value.saveDirectory,
                collectWaypoint = true,
                waypointPosition = labelPoint
            )
        }
        motionSensorManager.startMonitoring(this@FrontService)
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
            timer.runWifiTaskAtFrequency(
                frequencyY = _serviceState.value.wifiSamplingCycles.toDouble(),
                timestamp = currentTimeInText,
                dirName = _serviceState.value.saveDirectory,
                collectWaypoint = false,
            )
        }
        motionSensorManager.startMonitoring(this@FrontService)
        publishData("ack", data = AckData(deviceId = deviceId, ackInfo = "sample_on"))
    }

    fun stopCollectingData() {
        Log.e("STOP SAMPLING", "HERE")
        _serviceState.update { it.copy(isSampling = false) }
        timer.stopTask(apiBaseUrl = apiBaseUrl)
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

    fun startLocating() {
        _serviceState.update { it.copy(isLocatingStarted = true) }
        motionSensorManager.startMonitoring(this@FrontService)
        locatingServiceScope.launch {
            while (isServiceRunning) {
                val success = wifiManager.startScan()
                Log.d("StartLocating", "Triggered")
//                if (success) {
//                    _serviceState.update { it.copy(isLoadingStarted = true) }
//                }
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
        val wifiTimestamp = newValue[0].trimIndent().split(" ")[0].toLong()
        try {
//            var inputImuOffset = latestImuOffset ?: Offset(0f, 0f)
//            if (lastMag > 80 || (latestValidation != null && !latestValidation)) {
////                sysNoise = 4f
//                inputImuOffset = Offset(0f, 0f)
//            }

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
//                    _serviceState.update {
//                        it.copy(
//                            targetOffset = Offset(
//                                _serviceState.value.targetOffset.x + dx,
//                                _serviceState.value.targetOffset.y + dy
//                            )
//                        )
//                    }
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
//                    _serviceState.update {
//                        it.copy(
//                            targetOffset = Offset(
//                                _serviceState.value.targetOffset.x + dx,
//                                _serviceState.value.targetOffset.y + dy
//                            )
//                        )
//                    }
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