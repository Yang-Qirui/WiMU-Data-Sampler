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
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.core.app.NotificationCompat
import com.example.wimudatasampler.DataClass.Coordinate
import com.example.wimudatasampler.network.NetworkClient
import com.example.wimudatasampler.utils.CoroutineLockIndexedList
import com.example.wimudatasampler.utils.UserPreferencesKeys
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.math.pow
import kotlin.math.sqrt

class WifiScanningService : Service() {

    private val binder = LocalBinder()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var wifiManager: WifiManager
    private var wifiScanReceiver: BroadcastReceiver? = null

    // --- State to be exposed to the Activity ---
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _latestWifiScanResults = MutableStateFlow<List<String>>(emptyList())
    val latestWifiScanResults: StateFlow<List<String>> = _latestWifiScanResults.asStateFlow()

    // The initial installation default value of the persistent variable
    private var stride by mutableFloatStateOf(0.4f)
    private var beta by mutableFloatStateOf(0.9f)
    private var initialState = doubleArrayOf(0.0, 0.0)
    private var initialCovariance = arrayOf(
        doubleArrayOf(5.0, 0.0),
        doubleArrayOf(0.0, 1.0)
    )
    private var matrixQ = arrayOf(      // Process noise (prediction error)
        doubleArrayOf(0.05, 0.0),
        doubleArrayOf(0.0, 0.05)
    )
    private var matrixR = arrayOf(
        doubleArrayOf(4.65, 0.0),
        doubleArrayOf(0.0, 1.75)
    )
    private var matrixRPowOne = 2
    private var matrixRPowTwo = 2
    private var fullMatrixR = arrayOf(      // Observed noise
        doubleArrayOf(matrixR[0][0].pow(matrixRPowOne), matrixR[0][1]),
        doubleArrayOf(matrixR[1][0], matrixR[1][1].pow(matrixRPowTwo))
    )
    private val userHeight = 1.7f
    private val strideCoefficient = 0.414f
    private var estimatedStrideLength by mutableFloatStateOf(0f)
    private var estimatedStrides = mutableListOf<Float>()

    private var sysNoise = 1f
    private var obsNoise = 3f
    private var distFromLastPos = 0f

    private var period = 5f

    private var url = "http://limcpu1.cse.ust.hk:7860"
    private var azimuthOffset = 90f
    // The initial installation default value of the persistent variable

    private var imuOffset by mutableStateOf<Offset?>(null)
    private var imuOffsetHistory = CoroutineLockIndexedList<Offset, Int>()
    private var firstStart = true
    private var lastMag by mutableFloatStateOf(0f)
    private var targetOffset by mutableStateOf(Offset.Zero)
    private var lastOffset by mutableStateOf(Offset.Zero)
    private var latestStepCount by mutableIntStateOf(0)

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "WifiScanningServiceChannel"
        const val ACTION_START = "com.your.package.name.action.START"
        const val ACTION_STOP = "com.your.package.name.action.STOP"

        // 使用StateFlow来让外部（如Activity）可以观察服务是否在运行
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        createNotificationChannel()
        _isServiceRunning.value = true
        Log.d("WifiScanningService", "Service Created")

        // *** 在 Service 创建时开始观察 DataStore ***
        observeAppSettings()
    }


    private fun observeAppSettings() {
        serviceScope.launch {
            // 使用 applicationContext 访问 DataStore
            applicationContext.dataStore.data.collect { preferences ->
                Log.d("WifiScanningService", "Settings updated. New URL: ${preferences[UserPreferencesKeys.URL]}")

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
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("扫描已启动..."))
                startWifiScanningLoop()
                Log.d("WifiScanningService", "Service Started")
            }
            ACTION_STOP -> {
                Log.d("WifiScanningService", "Stopping service via action")
                stopService()
            }
        }
        return START_STICKY // 如果服务被杀死，系统会尝试重启它
    }

    // --- 逻辑函数，从Activity中移动过来 ---

    private fun startWifiScanningLoop() {
        if (_isScanning.value) return // 防止重复启动
        _isScanning.value = true

        registerWifiReceiver()

        serviceScope.launch {
            while (isActive) {
                wifiManager.startScan()
                delay((period * 1000).toLong())
            }
        }
    }

    private fun stopWifiScanningLoop() {
        _isScanning.value = false
        unregisterWifiReceiver()
        serviceScope.coroutineContext.cancelChildren() // 取消循环
    }

    @SuppressLint("MissingPermission")
    private fun registerWifiReceiver() {
        if (wifiScanReceiver != null) return
        wifiScanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    val scanResults = wifiManager.scanResults
                    if (scanResults.isNotEmpty()) {
                        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                        val minScanTime = scanResults.minOf { it.timestamp }
                        val resultList = scanResults.map { scanResult ->
                            "${(minScanTime / 1000 + bootTime)} ${scanResult.SSID} ${scanResult.BSSID} ${scanResult.frequency} ${scanResult.level}\n"
                        }
                        _latestWifiScanResults.value = resultList

                        // 在协程中处理网络请求和数据
                        serviceScope.launch {
                            onLatestWifiResultChanged(resultList)
                        }
                    }
                }
            }
        }
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)
    }

    private fun unregisterWifiReceiver() {
        if (wifiScanReceiver != null) {
            try {
                unregisterReceiver(wifiScanReceiver)
                wifiScanReceiver = null
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
    }

    // 这是你原有的处理逻辑，现在在Service中运行
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
            targetOffset = Offset(coordinate.x, coordinate.y)
            lastOffset = Offset(coordinate.x, coordinate.y)
            imuOffset = Offset(0f, 0f)
            imuOffsetHistory.clear()
            distFromLastPos = 0f
            firstStart = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopService() {
        stopWifiScanningLoop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        unregisterWifiReceiver()
        _isServiceRunning.value = false
        Log.d("WifiScanningService", "Service Destroyed")
    }

    // --- Binder and Notification ---

    inner class LocalBinder : Binder() {
        fun getService(): WifiScanningService = this@WifiScanningService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Wi-Fi Scanning Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        // 创建点击通知时打开MainActivity的Intent
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // 创建停止服务的Intent
        val stopSelfIntent = Intent(this, WifiScanningService::class.java).apply {
            action = ACTION_STOP
        }
        val pStopSelf = PendingIntent.getService(
            this, 0, stopSelfIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiMU 定位服务")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // 替换为你的图标
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止服务", pStopSelf) // 替换为你的停止图标
            .build()
    }
}