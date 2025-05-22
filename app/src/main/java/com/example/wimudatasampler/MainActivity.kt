package com.example.wimudatasampler

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wimudatasampler.DataClass.Coordinate
import com.example.wimudatasampler.Pages.MainScreen
import com.example.wimudatasampler.Pages.MapChoosingScreen
import com.example.wimudatasampler.Pages.SettingScreen
import com.example.wimudatasampler.navigation.MainActivityDestinations
import com.example.wimudatasampler.network.NetworkClient
import com.example.wimudatasampler.ui.theme.WiMUTheme
import com.example.wimudatasampler.utils.CircularArray
import com.example.wimudatasampler.utils.CoroutineLockIndexedList
import com.example.wimudatasampler.utils.KalmanFilter
import com.example.wimudatasampler.utils.Quadruple
import com.example.wimudatasampler.utils.SensorUtils
import com.example.wimudatasampler.utils.TimerUtils
import com.example.wimudatasampler.utils.lowPassFilter
import com.example.wimudatasampler.utils.validPostureCheck
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.client.statement.bodyAsText
import io.ktor.http.cio.Response
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.properties.Delegates

@AndroidEntryPoint
class MainActivity : ComponentActivity(), SensorUtils.SensorDataListener {

    private val mapViewModel: MapViewModel by viewModels()

    private lateinit var motionSensorManager: SensorUtils
    private lateinit var wifiManager: WifiManager
    private lateinit var wifiScanReceiver: BroadcastReceiver
    private lateinit var timer: TimerUtils
    private var startSamplingTime: String = ""
    private var lastRotationVector: FloatArray? = null
    private var rotationMatrix = FloatArray(9)
    private var lastStepCount: Float? = null
    private var lastStepCountFromMyStepDetector by mutableFloatStateOf(0f)
    private var lastAcc: FloatArray? = null
    private var lastGravity = FloatArray(3)
    private var lastGeomagnetic = FloatArray(3)
    private var lastMag by mutableFloatStateOf(0f)
    private var yaw by mutableFloatStateOf(0f)
    private var pitch by mutableFloatStateOf(0f)
    private var roll by mutableFloatStateOf(0f)
    private var latestWifiScanResults: List<String> by Delegates.observable(emptyList()) { property, oldValue, newValue ->
        if (newValue != oldValue && startInference) {
            scope.launch {
                onLatestWifiResultChanged(newValue)
            }
        }
    }
    private var startInference by mutableStateOf(false)
    private var isMonitoringAngles by mutableStateOf(false)
    private var showStepCountDialog by mutableStateOf(false)  // 控制弹窗显示状态
    private var stepCount by mutableFloatStateOf(0f)  // 保存步数值
    private var waypoints = mutableStateListOf<Offset>()
    private var wifiOffset by mutableStateOf<Offset?>(null)
    private var imuOffset by mutableStateOf<Offset?>(null)
    private var imuOffsetHistory = CoroutineLockIndexedList<Offset, Int>()
    private var stepCountHistory by mutableIntStateOf(0)
    private var latestStepCount by mutableIntStateOf(0)
    private var eulerHistory = CircularArray<Triple<Float, Float, Float>>(150, true)
    private var targetOffset by mutableStateOf(Offset.Zero)
    private var lastOffset by mutableStateOf(Offset.Zero)
    private var wifiScanningResults = mutableListOf<String>()
    private var navigationStarted by mutableStateOf(false)
    private var loadingStarted by mutableStateOf(false)
    private var enableImu by mutableStateOf(true)
    private var adjustedDegrees by mutableFloatStateOf(0f)
    private var enableMyStepDetector by mutableStateOf(false)
    private var accX by mutableFloatStateOf(0f)
    private var accY by mutableFloatStateOf(0f)
    private var accZ by mutableFloatStateOf(0f)
    private var filteredDirection = 0f
    private var firstStart = true
    private var alpha = 0.1f

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
    // The initial installation default value of the persistent variable

//    private val filter = KalmanFilter(initialState, initialCovariance, matrixQ, fullMatrixR)

    @RequiresApi(Build.VERSION_CODES.Q)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            val permission = currentRequestedPermission
            if (isGranted)
                Log.i("Permission", "Permission Granted for: $permission!")
            else Log.e("Permission", "Permission Denied for: $permission!")
            requestNextPermission()
        }
    private var currentRequestedPermission: String? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestNextPermission() {
        if (permissionsToRequest.isNotEmpty()) {
            val nextPermission = permissionsToRequest.removeAt(0)
            currentRequestedPermission = nextPermission
            requestPermissionLauncher.launch(nextPermission)
        }
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
                NetworkClient.fetchData(newValue, inputImuOffset, sysNoise, obsNoise)
            } else {
                NetworkClient.reset(newValue, sysNoise, obsNoise)
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACTIVITY_RECOGNITION,
    )

    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.IO + job)

    private fun startFetching() {
        loadingStarted = true
        motionSensorManager.startMonitoring(this)
        startInference = true
        scope.launch {
            while (true) {
                val success = wifiManager.startScan()
                // TODO: Support the newest wifi scanning logic
                if (success) {
                    loadingStarted = false
                    navigationStarted = true
                    delay((period * 1000).toLong())
                }
            }
        }
    }

    private fun endFetching() {
        motionSensorManager.stopMonitoring()
        imuOffset = null
        imuOffsetHistory.clear()
        stepCountHistory = 0
        eulerHistory.clear()
        startInference = false
        firstStart = true
        for (item in estimatedStrides) {
            Log.d("Stride", item.toString())
        }
        Log.d("Steps", "$stepCount, $lastStepCountFromMyStepDetector")
        job.cancel("Fetching stopped")
        job = Job()
        scope = CoroutineScope(Dispatchers.IO + job)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
    }

    private fun getBetaValue(): Float {
        return beta
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNextPermission()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge()
        setContent {
            WiMUTheme {
                val systemUiController = rememberSystemUiController()
                val surfaceVariantColor = MaterialTheme.colorScheme.surface

                val useDarkIcons = surfaceVariantColor.luminance() > 0.5

                SideEffect {
                    systemUiController.setStatusBarColor(
                        color = surfaceVariantColor,
                        darkIcons = useDarkIcons
                    )
                    window.navigationBarColor = surfaceVariantColor.toArgb()
                    window.navigationBarDividerColor = surfaceVariantColor.toArgb()
                }

                LaunchedEffect(Unit) {
                    this@MainActivity.dataStore.data.collect { preferences ->
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
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = MainActivityDestinations.Main.route
                    ) {
                        composable(MainActivityDestinations.Main.route) {
                            MainScreen(
                                mainContext = this@MainActivity,
                                context = this@MainActivity,
                                navController = navController,
                                mapViewModel = mapViewModel,
                                motionSensorManager = motionSensorManager,
                                wifiManager = wifiManager,
                                timer = timer,
                                setStartSamplingTime = { time -> startSamplingTime = time },
                                yaw = yaw,
                                pitch = pitch,
                                roll = roll,
                                orientation = adjustedDegrees,
                                isMonitoringAngles = isMonitoringAngles,
                                toggleMonitoringAngles = { toggleAngleMonitoring() },
                                waypoints = waypoints,
                                changeBeta = { value -> beta = value },
                                getBeta = { getBetaValue() },
                                targetOffset = targetOffset,
                                startFetching = { startFetching() },
                                endFetching = { endFetching() },
                                imuOffset = imuOffset,
                                wifiOffset = wifiOffset,
                                navigationStarted = navigationStarted,
                                loadingStarted = loadingStarted,
                                enableImu = enableImu,
                                onRefreshButtonClicked = {
                                    if (wifiOffset != null) {
                                        targetOffset = wifiOffset!!
                                    }
                                },
                                setNavigationStartFalse = { navigationStarted = false },
                                setLoadingStartFalse = { loadingStarted = false },
                                setEnableImu = { newValue -> enableImu = newValue},
                                estimatedStride = estimatedStrideLength,
                                accX = accX,
                                accY = accY,
                                accZ = accZ,
                                stepFromMyDetector = lastStepCountFromMyStepDetector,
                                setEnableMyStepDetector = {newValue -> enableMyStepDetector = newValue},
                                enableMyStepDetector = enableMyStepDetector,
                                mag = lastMag
                            )
                        }
                        composable(MainActivityDestinations.Settings.route) {
                            SettingScreen(
                                context = this@MainActivity,
                                navController = navController,
                                stride = stride,
                                beta = beta,
                                initialState = initialState,
                                initialCovariance = initialCovariance,
                                matrixQ = matrixQ,
                                matrixR = matrixR,
                                matrixRPowOne = matrixRPowOne,
                                matrixRPowTwo = matrixRPowTwo,
                                sysNoise = sysNoise,
                                obsNoise = obsNoise,
                                period = period,
                                updateStride = { newStride ->
                                    stride = newStride
                                },
                                updateBeta = { newBeta ->
                                    beta = newBeta
                                },
                                updateInitialState = { newInitialState ->
                                    initialState = newInitialState
                                },
                                updateInitialCovariance = { newInitialCovariance ->
                                    initialCovariance = newInitialCovariance
                                },
                                updateMatrixQ = { newMatrixQ ->
                                    matrixQ = newMatrixQ
                                },
                                updateMatrixR = { newMatrixR ->
                                    matrixR = newMatrixR
                                },
                                updateMatrixRPowOne = { newMatrixRPowOne ->
                                    matrixRPowOne = newMatrixRPowOne
                                },
                                updateMatrixRPowTwo = { newMatrixRPowTwo ->
                                    matrixRPowTwo = newMatrixRPowTwo
                                },
                                updateFullMatrixR = { newFullMatrixR  ->
                                    fullMatrixR = newFullMatrixR
                                },
                                updateSysNoise = { newSysNoise ->
                                    sysNoise = newSysNoise
                                },
                                updateObsNoise = { newObsNoise ->
                                    obsNoise = newObsNoise
                                },
                                updatePeriod = { newPeriod ->
                                    period = newPeriod
                                }
                            )
                        }
                        composable(MainActivityDestinations.MapChoosing.route) {
                            MapChoosingScreen(
                                context = this@MainActivity,
                                navController = navController,
                                mapViewModel = mapViewModel
                            )
                        }
                    }
                }
            }
        }

        wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
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
                        if (maxScanTime - minScanTime < 500_000_000) { //TODO
                            val resultList = scanResults.map { scanResult ->
                                "${(minScanTime / 1000 + bootTime)} ${scanResult.SSID} ${scanResult.BSSID} ${scanResult.frequency} ${scanResult.level}\n"
                            }
                            latestWifiScanResults = resultList
                            for (result in resultList) {
                                wifiScanningResults.add(result)
                                Log.d("RECEIVED_RES", result)
                            }
                        }
                    } else {
                        Log.e("RECEIVED", "No Wi-Fi scan results found")
                    }
                }
            }
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        timer = TimerUtils(scope, {wifiScanningResults}, {wifiScanningResults.clear()}, { wifiManager.startScan() }, this)
        motionSensorManager = SensorUtils(this)
        motionSensorManager.startMonitoring(this)
    }

    override fun onRotationVectorChanged(rotationVector: FloatArray) {
        lastRotationVector = rotationVector
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
        val orientations = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientations)
        yaw = Math.toDegrees(orientations[0].toDouble()).toFloat() + 90
        pitch = Math.toDegrees(orientations[1].toDouble()).toFloat()
        roll = Math.toDegrees(orientations[2].toDouble()).toFloat()
        eulerHistory.add(Triple(yaw, pitch, roll))
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
                val dx = -stride * cos(Math.toRadians(yaw.toDouble()).toFloat())
                val dy = -stride * sin(Math.toRadians(yaw.toDouble()).toFloat())
                distFromLastPos += stride
                val x = imuOffset!!.x + dx // north is negative axis
                val y = imuOffset!!.y + dy
                imuOffset = Offset(x, y)
                if (startInference && enableImu && validPostureCheck(pitch, roll)) {
                    imuOffsetHistory.put(
                        Quadruple(
                            System.currentTimeMillis(),
                            imuOffset!!,
                            stepCountHistory,
                            validPostureCheck(pitch, roll)
                        )
                    )
                    targetOffset = Offset(targetOffset.x + dx, targetOffset.y + dy)
                }
            }
        }
    }

    override fun onMyStepChanged() {
        lastStepCountFromMyStepDetector += 1
        if (enableMyStepDetector) {
            stepCountHistory += 1
            if (imuOffset != null) {
                val dx = -stride * cos(Math.toRadians(yaw.toDouble()).toFloat())
                val dy = -stride * sin(Math.toRadians(yaw.toDouble()).toFloat())
                distFromLastPos += stride
                val x = imuOffset!!.x + dx // north is negative axis
                val y = imuOffset!!.y + dy
                imuOffset = Offset(x, y)
                if (startInference && enableImu && validPostureCheck(pitch, roll)) {
                    imuOffsetHistory.put(
                        Quadruple(
                            System.currentTimeMillis(),
                            imuOffset!!,
                            stepCountHistory,
                            validPostureCheck(pitch, roll)
                        )
                    )
                    targetOffset = Offset(targetOffset.x + dx, targetOffset.y + dy)
                }
            }
        }
    }

    override fun onStepCountChanged(stepCount: Float) {
        lastStepCount = stepCount
        this.stepCount = stepCount  // 更新步数值
        showStepCountDialog = true  // 显示弹窗
    }

    override fun onMagChanged(mag: FloatArray) {
        lastGeomagnetic = lowPassFilter(mag.copyOf(), lastGeomagnetic)
        val x = mag[0]
        val y = mag[1]
        val z = mag[2]
        lastMag = sqrt(x * x + y * y + z * z)
    }

    override fun onAccChanged(acc: FloatArray) {
        lastGravity = lowPassFilter(acc.copyOf(), lastGravity)
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

    private fun toggleAngleMonitoring() {
        isMonitoringAngles = !isMonitoringAngles
        if (isMonitoringAngles) {
            motionSensorManager.startMonitoring(this)
        } else {
            motionSensorManager.stopMonitoring()
        }
    }
}


@Composable
fun FilledCardExample(
    offset: Offset,
    showingOffset: Offset, onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .padding(5.dp)
    ) {
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${showingOffset.x}, ${showingOffset.y}",
                modifier = Modifier.padding(vertical = 8.dp),
                textAlign = TextAlign.Center
            )
            Row {
                Button(onClick = onConfirm, Modifier.padding(5.dp)) {
                    Text("Yes")
                }
                Button(onClick = onDismiss, Modifier.padding(5.dp)) {
                    Text("No")
                }
            }
        }
    }
}


