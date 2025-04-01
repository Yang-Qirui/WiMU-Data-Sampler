package com.example.wimudatasampler

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.unit.dp
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableFloatStateOf
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wimudatasampler.DataClass.Coordinate
import com.example.wimudatasampler.HorizontalPage.InferenceHorizontalPage
import com.example.wimudatasampler.HorizontalPage.SampleHorizontalPage
import com.example.wimudatasampler.Pages.MainScreen
import com.example.wimudatasampler.Pages.MapChoosingScreen
import com.example.wimudatasampler.Pages.SettingScreen
import com.example.wimudatasampler.navigation.MainActivityDestinations
//import com.example.wimudatasampler.HorizontalPage.TrackingHorizontalPage
import com.example.wimudatasampler.network.NetworkClient
import com.example.wimudatasampler.ui.theme.WiMUTheme
import com.example.wimudatasampler.utils.KalmanFilter
import com.example.wimudatasampler.utils.Quadruple
import com.example.wimudatasampler.utils.SensorUtils
import com.example.wimudatasampler.utils.TimerUtils
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.math.pow
import kotlin.math.sqrt

@AndroidEntryPoint
class MainActivity : ComponentActivity(), SensorUtils.SensorDataListener {

    private val mapViewModel: MapViewModel by viewModels()

    private lateinit var motionSensorManager: SensorUtils
    private lateinit var wifiManager: WifiManager
    private lateinit var wifiScanReceiver: BroadcastReceiver
    private lateinit var timer: TimerUtils
    private var startSamplingTime: String = ""
    private var lastRotationVector: FloatArray? = null
    private var lastStepCount: Float? = null
    private var lastAcc: FloatArray? = null
    private var yaw by mutableFloatStateOf(0f)
    private var pitch by mutableFloatStateOf(0f)
    private var roll by mutableFloatStateOf(0f)
    private var isMonitoringAngles by mutableStateOf(false)
    private var showStepCountDialog by mutableStateOf(false)  // 控制弹窗显示状态
    private var stepCount by mutableFloatStateOf(0f)  // 保存步数值
    private var waypoints = mutableStateListOf<Offset>()
    private var trackingWaypoints = mutableStateListOf<Offset>()
    private var wifiOffset by mutableStateOf<Offset?>(null)
    private var imuOffset by mutableStateOf<Offset?>(null)
    private var targetOffset by mutableStateOf(Offset.Zero)

    private var navigationStarted by mutableStateOf(false)
    private var loadingStarted by mutableStateOf(false)

    // The initial installation default value of the persistent variable
    private var stride by mutableFloatStateOf(0.4f)
    private var beta by mutableFloatStateOf(1.0f)
    private var initialState = doubleArrayOf(0.0, 0.0)
    private var initialCovariance = arrayOf(
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
    // The initial installation default value of the persistent variable

    private val filter = KalmanFilter(initialState, initialCovariance, matrixQ, fullMatrixR)

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

    @RequiresApi(Build.VERSION_CODES.Q)
    private val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS
    )

    private var job = Job()
    private var scope = CoroutineScope(Dispatchers.IO + job)
    private fun euclideanDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x2 - x1).pow(2) + (y2 - y1).pow(2))
    }

    private fun startFetching() {
        var warmupCounter = 0
        var isInitialLoad = false
        var isWarmupCompleted = false
        scope.launch {
            while (true) {
                val (wifiResults, success, lastMinTimestamp) = wifiScan(wifiManager)
                if (success) {
                    if (!isInitialLoad) {
                        loadingStarted = true
                        isInitialLoad = true
                    }
                    Log.d("Map", "Wifi Scan Results")
//                    Log.d("BETA", beta.toString())
                    try {
                        val response = NetworkClient.fetchData(wifiResults)
                        Log.d("response", response.bodyAsText())
                        var coordinate: Coordinate?
                        try {
                            coordinate = Json.decodeFromString<Coordinate>(response.bodyAsText())
                        } catch (e: Exception) {
                            delay(5000)
                            continue
                        }
                        wifiOffset = Offset(coordinate.x, coordinate.y)

                        Log.d("wifiOffset", wifiOffset.toString())
                        if (warmupCounter > 2) {
                            if (!isWarmupCompleted) {
                                navigationStarted = true
                                loadingStarted = false
                                isWarmupCompleted = true
                            }
                            if (imuOffset == null) {
                                imuOffset = Offset(coordinate.x, coordinate.y)
                                filter.setInit(
                                    doubleArrayOf(
                                        coordinate.x.toDouble(),
                                        coordinate.y.toDouble()
                                    )
                                )
                                targetOffset = Offset(coordinate.x, coordinate.y)
                            } else if (imuOffset != null) {
                                filter.update(
                                    doubleArrayOf(
                                        coordinate.x.toDouble(),
                                        coordinate.y.toDouble()
                                    )
                                )
                                val (finalX, finalY) = filter.getState()
                                targetOffset = Offset(finalX.toFloat(), finalY.toFloat())
                                imuOffset = Offset(finalX.toFloat(), finalY.toFloat())
                            }
                        }
                        warmupCounter += 1
                    } catch (e: Exception) {
                        Log.e("Http exception", e.toString())
                    }
                }
                delay(5000)
            }
        }
    }

    private fun endFetching() {
        job.cancel("Fetching stopped")
        job.cancel()
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
                                onRefreshButtonClicked = {
                                    if (wifiOffset != null) {
                                        targetOffset = wifiOffset!!
                                    }
                                },
                                setNavigationStartFalse = { navigationStarted = false },
                                setLoadingStartFalse = { loadingStarted = false }
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
            override fun onReceive(context: Context, intent: Intent) {}
        }

        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter, RECEIVER_NOT_EXPORTED)

        timer = TimerUtils(scope, this)
        motionSensorManager = SensorUtils(this)
        motionSensorManager.startMonitoring(this)
    }

    override fun onRotationVectorChanged(rotationVector: FloatArray) {
        lastRotationVector = rotationVector
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
        val orientations = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientations)
        yaw = Math.toDegrees(orientations[0].toDouble()).toFloat()
        pitch = Math.toDegrees(orientations[1].toDouble()).toFloat()
        roll = Math.toDegrees(orientations[2].toDouble()).toFloat()
    }

    override fun onSingleStepChanged() {
        if (imuOffset != null) {
            val x = imuOffset!!.x - stride * cos(
                Math.toRadians(yaw.toDouble()).toFloat()
            ) // north is negative axis
            val y = imuOffset!!.y - stride * sin(Math.toRadians(yaw.toDouble()).toFloat())
            imuOffset = Offset(x, y)
            filter.predict(stride, Math.toRadians(yaw.toDouble()).toFloat())
            val (imuX, imuY) = filter.getState()
            targetOffset = Offset(imuX.toFloat(), imuY.toFloat())
        }
    }

    override fun onStepCountChanged(stepCount: Float) {
        lastStepCount = stepCount
        this.stepCount = stepCount  // 更新步数值
        showStepCountDialog = true  // 显示弹窗
    }

    override fun onAccChanged(acc: FloatArray) {
        lastAcc = acc
        this.lastAcc = acc
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

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun wifiScan(wifiManager: WifiManager): Quadruple<String, Boolean, Long, String> {
    val success = wifiManager.startScan()
    if (success) {
        val scanResults = wifiManager.scanResults
        // TODO: currentTimeMillis change
        val currentTime = System.currentTimeMillis()
        val resultList = scanResults.map { scanResult ->
            "$currentTime ${scanResult.SSID} ${scanResult.BSSID} ${scanResult.frequency} ${scanResult.level}"
        }
        val resultString = resultList.joinToString("\n")  // 将每个结果拼接为字符串，使用换行分隔
        val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val maxTimestamp = scanResults.maxOf { it.timestamp }
        val minTimestamp = scanResults.minOf { it.timestamp }
        Log.d("min ts", "$minTimestamp ")
        Log.d("DIFF", "${(maxTimestamp - minTimestamp) / 1_000_000}")
        Log.d("Debug", "${currentTime - (bootTime + maxTimestamp / 1_000)}")
        if (currentTime - (bootTime + maxTimestamp / 1_000) < 500) {
            return Quadruple(resultString, true, minTimestamp, "Success")
        }
        else {
            return Quadruple("", false, 0, "Failed due to interval gap: ${currentTime - (bootTime + maxTimestamp / 1_000)}")
        }
    }
    return Quadruple("", false, 0, "Scanning failed")
}

suspend fun saveUserPreferences(
    context: Context,
    stride: Float,
    beta: Float,
    initialState: DoubleArray,
    initialCovariance: Array<DoubleArray>,
    matrixQ: Array<DoubleArray>,
    matrixR: Array<DoubleArray>,
    matrixRPowOne: Int,
    matrixRPowTwo: Int
) {
    context.dataStore.edit { preferences ->
        preferences[UserPreferencesKeys.STRIDE] = stride

        preferences[UserPreferencesKeys.BETA] = beta

        preferences[UserPreferencesKeys.INITIAL_STATE_1] = initialState[0]
        preferences[UserPreferencesKeys.INITIAL_STATE_2] = initialState[1]

        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_1] = initialCovariance[0][0]
        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_2] = initialCovariance[0][1]
        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_3] = initialCovariance[1][0]
        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_4] = initialCovariance[1][1]

        preferences[UserPreferencesKeys.MATRIX_Q_1] = matrixQ[0][0]
        preferences[UserPreferencesKeys.MATRIX_Q_2] = matrixQ[0][1]
        preferences[UserPreferencesKeys.MATRIX_Q_3] = matrixQ[1][0]
        preferences[UserPreferencesKeys.MATRIX_Q_4] = matrixQ[1][1]

        preferences[UserPreferencesKeys.MATRIX_R_1] = matrixR[0][0]
        preferences[UserPreferencesKeys.MATRIX_R_2] = matrixR[0][1]
        preferences[UserPreferencesKeys.MATRIX_R_3] = matrixR[1][0]
        preferences[UserPreferencesKeys.MATRIX_R_4] = matrixR[1][1]

        preferences[UserPreferencesKeys.MATRIX_R_POW_1] = matrixRPowOne
        preferences[UserPreferencesKeys.MATRIX_R_POW_2] = matrixRPowTwo
    }
}


