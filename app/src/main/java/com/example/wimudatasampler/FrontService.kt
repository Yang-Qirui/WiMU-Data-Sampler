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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.core.app.NotificationCompat
import com.example.wimudatasampler.DataClass.Coordinate
import com.example.wimudatasampler.network.NetworkClient
import com.example.wimudatasampler.utils.CoroutineLockIndexedList
import com.example.wimudatasampler.utils.Quadruple
import com.example.wimudatasampler.utils.SensorUtils
import com.example.wimudatasampler.utils.TimerUtils
import com.example.wimudatasampler.utils.UserPreferencesKeys
import com.example.wimudatasampler.utils.lowPassFilter
import com.example.wimudatasampler.utils.validPostureCheck
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Locale

data class ServiceState(
    //UI State
    var isCollectTraining: Boolean = false,
    var isSampling:Boolean = false,
    var isLocatingStarted:Boolean = false,
    var isLoadingStarted:Boolean = false,
    var isImuEnabled:Boolean=true,
    var isMyStepDetectorEnabled:Boolean=false,
    //Sampling Page Data
    val yaw: Float = 0.0F,
    val pitch: Float = 0.0F,
    val roll: Float = 0.0F,
    val numOfLabelSampling:Int? = null, // Start from 0
    val wifiScanningInfo:String?=null,
    var wifiSamplingCycles: Float = 3.0F,
    var sensorSamplingCycles: Float = 0.05F,
    var saveDirectory: String = "",
    var targetOffset:Offset=Offset.Zero, // User's physical location
    val userHeading:Float?=null, // User orientation Angle (0-360)
    val waypoints: SnapshotStateList<Offset> = mutableStateListOf(),
    var imuOffset:Offset?=null,
    //此处可添加更多需要暴露给软件UI的值
    //...
)

class FrontService : Service(), SensorUtils.SensorDataListener {

    private lateinit var motionSensorManager: SensorUtils
    lateinit var wifiManager: WifiManager
    private lateinit var wifiScanReceiver: BroadcastReceiver
    private lateinit var timer: TimerUtils

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
    private var latestWifiScanResults: List<String> = emptyList()
    private var wifiScanningResults = mutableListOf<String>()
    private var imuOffsetHistory = CoroutineLockIndexedList<Offset, Int>()
    private var stepCountHistory = 0
    private var latestStepCount = 0
    private var lastOffset = Offset.Zero
    private var firstStart = true
    private var adjustedDegrees by mutableFloatStateOf(0f)

    private var alpha = 0.1f
    private var filteredDirection = 0f

    // 持久化的变量
    var stride by mutableFloatStateOf(0.4f)
    var beta by mutableFloatStateOf(0.9f)
    var initialState = doubleArrayOf(0.0, 0.0)
    var initialCovariance = arrayOf(
        doubleArrayOf(5.0, 0.0),
        doubleArrayOf(0.0, 1.0)
    )
    var matrixQ = arrayOf(      // Process noise (prediction error)
        doubleArrayOf(0.05, 0.0),
        doubleArrayOf(0.0, 0.05)
    )
    var matrixR = arrayOf(
        doubleArrayOf(4.65, 0.0),
        doubleArrayOf(0.0, 1.75)
    )
    var matrixRPowOne = 2
    var matrixRPowTwo = 2
    var fullMatrixR = arrayOf(      // Observed noise
        doubleArrayOf(matrixR[0][0].pow(matrixRPowOne), matrixR[0][1]),
        doubleArrayOf(matrixR[1][0], matrixR[1][1].pow(matrixRPowTwo))
    )
    val userHeight = 1.7f
    val strideCoefficient = 0.414f
    var estimatedStrideLength by mutableFloatStateOf(0f)
    var estimatedStrides = mutableListOf<Float>()

    var sysNoise = 1f
    var obsNoise = 3f
    var distFromLastPos = 0f

    var period = 5f

    var url = "http://limcpu1.cse.ust.hk:7860"
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

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            dataStore.data.collect { preferences ->
                stride = preferences[UserPreferencesKeys.STRIDE] ?: stride
                beta = preferences[UserPreferencesKeys.BETA] ?: beta
                initialState = doubleArrayOf(
                    preferences[UserPreferencesKeys.INITIAL_STATE_1]
                        ?: initialState[0],
                    preferences[UserPreferencesKeys.INITIAL_STATE_2]
                        ?: initialState[1]
                )
                initialCovariance = arrayOf(
                    doubleArrayOf(
                        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_1]
                            ?: initialCovariance[0][0],
                        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_2]
                            ?: initialCovariance[0][1]
                    ),
                    doubleArrayOf(
                        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_3]
                            ?: initialCovariance[1][0],
                        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_4]
                            ?: initialCovariance[1][1]
                    ),
                )
                matrixQ = arrayOf(
                    doubleArrayOf(
                        preferences[UserPreferencesKeys.MATRIX_Q_1]
                            ?: matrixQ[0][0],
                        preferences[UserPreferencesKeys.MATRIX_Q_2]
                            ?: matrixQ[0][1]
                    ),
                    doubleArrayOf(
                        preferences[UserPreferencesKeys.MATRIX_Q_3]
                            ?: matrixQ[1][0],
                        preferences[UserPreferencesKeys.MATRIX_Q_4]
                            ?: matrixQ[1][1]
                    ),
                )
                matrixR = arrayOf(
                    doubleArrayOf(
                        preferences[UserPreferencesKeys.MATRIX_R_1]
                            ?: matrixR[0][0],
                        preferences[UserPreferencesKeys.MATRIX_R_2]
                            ?: matrixR[0][1]
                    ),
                    doubleArrayOf(
                        preferences[UserPreferencesKeys.MATRIX_R_3]
                            ?: matrixR[1][0],
                        preferences[UserPreferencesKeys.MATRIX_R_4]
                            ?: matrixR[1][1]
                    ),
                )
                matrixRPowOne =
                    preferences[UserPreferencesKeys.MATRIX_R_POW_1] ?: matrixRPowOne
                matrixRPowTwo =
                    preferences[UserPreferencesKeys.MATRIX_R_POW_2] ?: matrixRPowTwo
                fullMatrixR = arrayOf(      // 观测噪声
                    doubleArrayOf(matrixR[0][0].pow(matrixRPowOne), matrixR[0][1]),
                    doubleArrayOf(matrixR[1][0], matrixR[1][1].pow(matrixRPowTwo))
                )

                sysNoise = preferences[UserPreferencesKeys.SYS_NOISE] ?: sysNoise
                obsNoise = preferences[UserPreferencesKeys.OBS_NOISE] ?: obsNoise

                url = preferences[UserPreferencesKeys.URL] ?:url

                azimuthOffset = preferences[UserPreferencesKeys.AZIMUTH_OFFSET] ?: azimuthOffset
            }
        }

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
                            val resultList = scanResults.map { scanResult ->
                                "${(minScanTime / 1000 + bootTime)} ${scanResult.SSID} ${scanResult.BSSID} ${scanResult.frequency} ${scanResult.level}\n"
                            }
                            latestWifiScanResults = resultList
                            if (_serviceState.value.isLocatingStarted) {
                                locatingServiceScope.launch { onLatestWifiResultChanged(resultList) }
                            }
                        }
                    } else {
                        Log.e("RECEIVED", "No Wi-Fi scan results found")
                    }
                }
            }
        }
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        timer = TimerUtils(samplingServiceScope, {wifiScanningResults}, {wifiScanningResults.clear()}, { wifiManager.startScan() }, this@FrontService)
        motionSensorManager = SensorUtils(this@FrontService)
        motionSensorManager.startMonitoring(this@FrontService)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocating()
        stopCollectingData()
        motionSensorManager.stopMonitoring()
        timer.stopTask()
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
        _serviceState.value.wifiSamplingCycles = newValue
    }

    fun updateSensorSamplingCycles(newValue: Float) {
        _serviceState.value.sensorSamplingCycles = newValue
    }

    fun updateSaveDirectory(newValue: String) {
        _serviceState.value.saveDirectory = newValue
    }

    fun updateIsCollectTraining(newValue: Boolean) {
        _serviceState.value.isCollectTraining = newValue
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
        _serviceState.value.saveDirectory = "Waypoint-${indexOfLabel + 1}-$currentTimeInText"
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
                wifiManager = wifiManager,
                frequencyY = _serviceState.value.wifiSamplingCycles.toDouble(),
                timestamp = currentTimeInText,
                dirName = _serviceState.value.saveDirectory,
                collectWaypoint = true,
                waypointPosition = labelPoint
            )
        }
        motionSensorManager.startMonitoring(this@FrontService)
        _serviceState.value.isSampling = true
    }

    fun startCollectingUnLabelData(startTimestamp: Long) {
        timer.setSavingDir("Unlabeled")
        val currentTimeInText =
            SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(
                startTimestamp
            )
        _serviceState.value.saveDirectory = "Trajectory-$currentTimeInText"
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
                wifiManager = wifiManager,
                frequencyY = _serviceState.value.wifiSamplingCycles.toDouble(),
                timestamp = currentTimeInText,
                dirName = _serviceState.value.saveDirectory,
                collectWaypoint = false,
            )
        }
        motionSensorManager.startMonitoring(this@FrontService)
        _serviceState.value.isSampling = true
    }

    fun stopCollectingData() {
        _serviceState.value.isSampling = false
        timer.stopTask()
        samplingServiceJob.cancelChildren()
        if (!_serviceState.value.isLocatingStarted && !_serviceState.value.isLoadingStarted) {
            motionSensorManager.stopMonitoring()
        }
    }

    fun enableImuSensor() {
        _serviceState.value.isImuEnabled = true
    }

    fun disableImuSensor() {
        _serviceState.value.isImuEnabled = false
    }

    fun enableOwnStepCounter() {
        _serviceState.value.isMyStepDetectorEnabled = true
    }

    fun disableOwnStepCounter() {
        _serviceState.value.isMyStepDetectorEnabled = false
    }

    fun startLocating() {
        _serviceState.value.isLocatingStarted = true
        motionSensorManager.startMonitoring(this@FrontService)
        locatingServiceScope.launch {
            while (isServiceRunning) {
                val success = wifiManager.startScan()
                if (success) {
                    _serviceState.value.isLoadingStarted = true
                }
                delay((period * 1000).toLong())
            }
        }
    }

    fun refreshLocating() {
        if (wifiOffset != null) {
            _serviceState.value.targetOffset = wifiOffset!!
        }
    }

    fun stopLocating() {
        if (!isServiceRunning) return

        locatingServiceJob.cancelChildren()
        if (!_serviceState.value.isSampling) {
            motionSensorManager.stopMonitoring()
        }

        _serviceState.value.isLoadingStarted = false
        _serviceState.value.isLocatingStarted = false
    }

    private suspend fun onLatestWifiResultChanged(newValue: List<String>) {
        val wifiTimestamp = newValue[0].trimIndent().split(" ")[0].toLong()
//        for (item in imuOffsetHistory.list) {
//            Log.d("item", item.toString())
//        }
        val closestRecord = imuOffsetHistory.get(wifiTimestamp)
        val latestImuOffset = closestRecord?.second
        val latestTimestamp = closestRecord?.third
        val latestValidation = closestRecord?.fourth
//        Log.d("Last", latestImuOffset.toString())
        try {
            var inputImuOffset = latestImuOffset ?: Offset(0f, 0f)
            if (lastMag > 80 || (latestValidation != null && !latestValidation)) {
//                sysNoise = 4f
                inputImuOffset = Offset(0f, 0f)
            }
            val response = if (!firstStart) {
                NetworkClient.fetchData(
                    url = url,
                    wifiResult = newValue,
                    imuInput = inputImuOffset,
                    sysNoise = sysNoise,
                    obsNoise = obsNoise
                )
            } else {
                NetworkClient.reset(
                    url = url,
                    wifiResult = newValue,
                    sysNoise = sysNoise,
                    obsNoise = obsNoise
                )
            }
            Log.d("current pos", response.bodyAsText())
            val coordinate = Json.decodeFromString<Coordinate>(response.bodyAsText())
//            Log.d("last pos", "${lastOffset.x}, ${lastOffset.y}")
            if (!firstStart && latestTimestamp != null && latestTimestamp - latestStepCount != 0) {
                val delta = sqrt((inputImuOffset.x + lastOffset.x - coordinate.x).pow(2) + (inputImuOffset.y + lastOffset.y - coordinate.y).pow(2))
                Log.d("Delta", "$delta, ${distFromLastPos + delta}")

                val estimatedStride = (distFromLastPos + delta) / (latestTimestamp - latestStepCount)
                Log.d("Est", estimatedStride.toString())
                Log.d("Step diff", "${latestTimestamp - latestStepCount}")
                latestStepCount = latestTimestamp
                if (0.45 < estimatedStride && estimatedStride < 0.6) {
                    stride = (1 - beta) * stride + beta * estimatedStride
                }
                estimatedStrides.               add(estimatedStride)
            }
            Log.e("TARGET OFFSET", "${coordinate.x}, ${coordinate.y}")
            _serviceState.value.targetOffset = Offset(coordinate.x, coordinate.y)
            this.lastOffset = Offset(coordinate.x, coordinate.y)
            _serviceState.value.imuOffset = Offset(0f, 0f)
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
        _serviceState.update { it.copy(yaw = newYaw, pitch = newPitch, roll = newRoll) }
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
                val dx = -stride * cos(Math.toRadians(currentYaw.toDouble()).toFloat())
                val dy = -stride * sin(Math.toRadians(currentYaw.toDouble()).toFloat())
                distFromLastPos += stride
                val x = _serviceState.value.imuOffset!!.x + dx
                val y = _serviceState.value.imuOffset!!.y + dy
                _serviceState.value.imuOffset = Offset(x, y)

                if (isServiceRunning && _serviceState.value.isImuEnabled && validPostureCheck(
                        _serviceState.value.pitch,
                        _serviceState.value.roll
                    )
                ) {
                    imuOffsetHistory.put(
                        Quadruple(
                            System.currentTimeMillis(),
                            _serviceState.value.imuOffset!!,
                            stepCountHistory,
                            validPostureCheck(_serviceState.value.pitch, _serviceState.value.roll)
                        )
                    )
                    _serviceState.value.targetOffset = Offset(_serviceState.value.targetOffset.x + dx, _serviceState.value.targetOffset.y + dy)
                }
            }
        }
    }

    override fun onMyStepChanged() {
        lastStepCountFromMyStepDetector += 1
        if (_serviceState.value.isMyStepDetectorEnabled) {
            stepCountHistory += 1
            if (_serviceState.value.imuOffset != null) {
                val dx = -stride * cos(Math.toRadians(_serviceState.value.yaw.toDouble()).toFloat())
                val dy = -stride * sin(Math.toRadians(_serviceState.value.yaw.toDouble()).toFloat())
                distFromLastPos += stride
                val x = _serviceState.value.imuOffset!!.x + dx // north is negative axis
                val y = _serviceState.value.imuOffset!!.y + dy
                _serviceState.value.imuOffset = Offset(x, y)
                if (isServiceRunning && _serviceState.value.isMyStepDetectorEnabled && validPostureCheck(_serviceState.value.pitch, _serviceState.value.roll)) {
                    imuOffsetHistory.put(
                        Quadruple(
                            System.currentTimeMillis(),
                            _serviceState.value.imuOffset!!,
                            stepCountHistory,
                            validPostureCheck(_serviceState.value.pitch, _serviceState.value.roll)
                        )
                    )
                    _serviceState.value.targetOffset = Offset(_serviceState.value.targetOffset.x + dx, _serviceState.value.targetOffset.y + dy)
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
}