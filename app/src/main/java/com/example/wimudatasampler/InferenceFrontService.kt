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
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.wimudatasampler.DataClass.Coordinate
import com.example.wimudatasampler.network.NetworkClient
import com.example.wimudatasampler.utils.CoroutineLockIndexedList
import com.example.wimudatasampler.utils.Quadruple
import com.example.wimudatasampler.utils.SensorUtils
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

data class ServiceState(
    val navigationStarted: Boolean = false,
    val loadingStarted: Boolean = false,
    val targetOffset: Offset = Offset.Zero,
    val imuOffset: Offset? = null,
    val estimatedStrideLength: Float = 0f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val accX: Float = 0f,
    val accY: Float = 0f,
    val accZ: Float = 0f,
    val mag: Float = 0f,
    val stepFromMyDetector: Float = 0f
)

class InferenceFrontService : Service(), SensorUtils.SensorDataListener {

    lateinit var motionSensorManager: SensorUtils
    lateinit var wifiManager: WifiManager
    private lateinit var wifiScanReceiver: BroadcastReceiver

    // Coroutine Scope for the service
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // StateFlow to communicate with the UI (Activity)
    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState = _serviceState.asStateFlow()

    // --- Start: All state variables moved from MainActivity ---
    private var lastRotationVector: FloatArray? = null
    private var rotationMatrix = FloatArray(9)
    private var lastStepCount: Float? = null
    private var lastStepCountFromMyStepDetector = 0f
    private var lastGravity = FloatArray(3)
    private var lastGeomagnetic = FloatArray(3)
    private var latestWifiScanResults: List<String> = emptyList()

    private var imuOffset: Offset? = null
    private var imuOffsetHistory = CoroutineLockIndexedList<Offset, Int>()
    private var stepCountHistory = 0
    private var latestStepCount = 0
    private var targetOffset = Offset.Zero
    private var lastOffset = Offset.Zero
    private var firstStart = true

    private var alpha = 0.1f
    private var filteredDirection = 0f

    // Parameters (can be updated from settings via binding in the future if needed)
    private var stride = 0.4f
    private var beta = 0.9f
    private var sysNoise = 1f
    private var obsNoise = 3f
    private var distFromLastPos = 0f
    private var period = 5f
    private var url = "http://limcpu1.cse.ust.hk:7860"
    private var azimuthOffset = 90f
    private var enableMyStepDetector = false // You might want to control this from the UI
    private var enableImu = true
    private var estimatedStrides = mutableListOf<Float>()
    // --- End: All state variables moved from MainActivity ---


    companion object {
        const val ACTION_START = "com.example.wimudatasampler.action.START"
        const val ACTION_STOP = "com.example.wimudatasampler.action.STOP"
        private const val NOTIFICATION_CHANNEL_ID = "LocationServiceChannel"
        private const val NOTIFICATION_ID = 1
        var isRunning = false
    }

    private val binder = LocationBinder()

    inner class LocationBinder : Binder() {
        fun getService(): InferenceFrontService = this@InferenceFrontService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        motionSensorManager = SensorUtils(this)
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        wifiScanReceiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    Log.d("RECEIVED", "Received at ${SystemClock.elapsedRealtime()}")
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
                            if (isRunning) {
                                serviceScope.launch { onLatestWifiResultChanged(resultList) }
                            }
                        }
                    }
                }
            }
        }
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLocationUpdates()
            ACTION_STOP -> stopLocationUpdates()
        }
        return START_STICKY // If the service is killed, it will be automatically restarted.
    }

    private fun startLocationUpdates() {
        if (isRunning) return
        isRunning = true

        startForeground(NOTIFICATION_ID, createNotification())

        _serviceState.update { it.copy(loadingStarted = true) }
        motionSensorManager.startMonitoring(this)

        serviceScope.launch {
            while (isRunning) {
                val success = wifiManager.startScan()
                if (success) {
                    _serviceState.update { it.copy(loadingStarted = false, navigationStarted = true) }
                }
                delay((period * 1000).toLong())
            }
        }
    }

    private fun stopLocationUpdates() {
        if (!isRunning) return

        motionSensorManager.stopMonitoring()
        imuOffset = null
        imuOffsetHistory.clear()
        stepCountHistory = 0
        firstStart = true
        _serviceState.value = ServiceState() // Reset state

        // Log final data if needed
        for (item in estimatedStrides) {
            Log.d("Stride", item.toString())
        }

        serviceJob.cancelChildren() // Cancel all coroutines
        isRunning = false
        stopForeground(true)
        stopSelf()
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
            val currentMag = _serviceState.value.mag
            if (currentMag > 80 || (latestValidation != null && !latestValidation)) {
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
            Log.d("last pos", "${lastOffset.x}, ${lastOffset.y}")
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
                estimatedStrides.add(estimatedStride)
            }
            _serviceState.update { currentState ->
                currentState.copy(targetOffset = Offset(coordinate.x, coordinate.y))
            }
            // Update other state variables like lastOffset, imuOffset etc.
            this.targetOffset = Offset(coordinate.x, coordinate.y)
            this.lastOffset = Offset(coordinate.x, coordinate.y)
            this.imuOffset = Offset(0f,0f)
            this.imuOffsetHistory.clear()
            this.distFromLastPos = 0f
            this.firstStart = false

        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        val stopIntent = Intent(this, InferenceFrontService::class.java).apply {
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

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        unregisterReceiver(wifiScanReceiver)
        serviceJob.cancel()
        isRunning = false
    }

    // --- Implement SensorDataListener methods ---
    // Move all `on...Changed` methods from MainActivity here.
    // Inside these methods, update the state variables and the `_serviceState` flow.

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
        if (!enableMyStepDetector) {
            stepCountHistory += 1
//            Log.d("STEP COUNT", "$stepCountHistory, $stepCount, $lastStepCountFromMyStepDetector")
            if (imuOffset != null) {
                val currentYaw = _serviceState.value.yaw
                val dx = -stride * cos(Math.toRadians(currentYaw.toDouble()).toFloat())
                val dy = -stride * sin(Math.toRadians(currentYaw.toDouble()).toFloat())
                distFromLastPos += stride
                val x = imuOffset!!.x + dx
                val y = imuOffset!!.y + dy
                imuOffset = Offset(x, y)
                // update service state
                _serviceState.update {
                    it.copy(
                        imuOffset = this.imuOffset
                    )
                }

                if (isRunning && enableImu && validPostureCheck(
                        _serviceState.value.pitch,
                        _serviceState.value.roll
                    )
                ) {
                    imuOffsetHistory.put(
                        Quadruple(
                            System.currentTimeMillis(),
                            imuOffset!!,
                            stepCountHistory,
                            validPostureCheck(_serviceState.value.pitch, _serviceState.value.roll)
                        )
                    )
                    targetOffset = Offset(targetOffset.x + dx, targetOffset.y + dy)
                    _serviceState.update {
                        it.copy(
                            targetOffset = targetOffset
                        )
                    }
                }
            }
        }
    }

    override fun onMyStepChanged() {
        lastStepCountFromMyStepDetector += 1
        _serviceState.update { it.copy(stepFromMyDetector = lastStepCountFromMyStepDetector) }
        if (enableMyStepDetector) {
            stepCountHistory += 1
            if (imuOffset != null) {
                val currentState = _serviceState.value
                val currentYaw = currentState.yaw
                val currentPitch = currentState.pitch
                val currentRoll = currentState.roll
                val dx = -stride * cos(Math.toRadians(_serviceState.value.yaw.toDouble()).toFloat())
                val dy = -stride * sin(Math.toRadians(_serviceState.value.yaw.toDouble()).toFloat())
                distFromLastPos += stride
                val x = imuOffset!!.x + dx // north is negative axis
                val y = imuOffset!!.y + dy
                imuOffset = Offset(x, y)
                _serviceState.update {
                    it.copy(
                        imuOffset = imuOffset
                    )
                }
                if (isRunning && enableImu && validPostureCheck(currentPitch, currentRoll)) {
                    imuOffsetHistory.put(
                        Quadruple(
                            System.currentTimeMillis(),
                            imuOffset!!,
                            stepCountHistory,
                            validPostureCheck(currentPitch, currentRoll)
                        )
                    )
                    targetOffset = Offset(targetOffset.x + dx, targetOffset.y + dy)
                    _serviceState.update {
                        it.copy(
                            targetOffset = targetOffset
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
        val x = mag[0]; val y = mag[1]; val z = mag[2]
        val newMag = sqrt(x * x + y * y + z * z)
        _serviceState.update { it.copy(mag = newMag) }
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
        _serviceState.update { it.copy(accX = acc[0], accY = acc[1], accZ = acc[2]) }
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
        val adjustedDegrees = Math.toDegrees(filteredDirection.toDouble()).toFloat()
//        val angleFromNorth = ((90 - Math.toDegrees(angleFromEast)) % 360).toFloat()
//        adjustedDegrees = if (angleFromNorth > 180) angleFromNorth - 360 else angleFromNorth
//        adjustedDegrees = (degrees + 360) % 360
    }

}