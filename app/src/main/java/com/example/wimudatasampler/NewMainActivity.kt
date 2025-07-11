package com.example.wimudatasampler

import android.Manifest
import dagger.hilt.android.AndroidEntryPoint

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wimudatasampler.Pages.MainScreen
import com.example.wimudatasampler.Pages.MapChoosingScreen
import com.example.wimudatasampler.Pages.SettingScreen
import com.example.wimudatasampler.navigation.MainActivityDestinations
import com.example.wimudatasampler.ui.theme.WiMUTheme
import com.example.wimudatasampler.utils.TimerUtils
import com.example.wimudatasampler.utils.UserPreferencesKeys
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import kotlinx.coroutines.launch
import kotlin.math.pow

@AndroidEntryPoint
class NewMainActivity : ComponentActivity() {

    private val mapViewModel: MapViewModel by viewModels()

    // Service connection
    private var inferenceFrontService: InferenceFrontService? = null
    private var isBound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as InferenceFrontService.LocationBinder
            inferenceFrontService = binder.getService()
            isBound = true
            // Start collecting state updates from the service
            lifecycleScope.launch {
                inferenceFrontService?.serviceState?.collect { newState ->
                    // Update UI state based on service state
                    serviceState = newState
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            inferenceFrontService = null
        }
    }

    // UI State, now driven by the service
    private var serviceState by mutableStateOf(ServiceState())

    // --- Retain only the necessary variables for UI and settings ---
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
    // ... all your other settings variables (beta, matrixQ, etc.) remain here

    // Permissions logic remains in the Activity
    @RequiresApi(Build.VERSION_CODES.TIRAMISU) // Changed to Tiramisu for POST_NOTIFICATIONS
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            val permission = currentRequestedPermission
            if (isGranted)
                Log.i("Permission", "Permission Granted for: $permission!")
            else Log.e("Permission", "Permission Denied for: $permission!")
            requestNextPermission()
        }

    private var currentRequestedPermission: String? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestNextPermission() {
        if (permissionsToRequest.isNotEmpty()) {
            val nextPermission = permissionsToRequest.removeAt(0)
            currentRequestedPermission = nextPermission
            requestPermissionLauncher.launch(nextPermission)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.POST_NOTIFICATIONS
    )

    override fun onStart() {
        super.onStart()
        // Bind to the service if it's running
        if (InferenceFrontService.isRunning) {
            Intent(this, InferenceFrontService::class.java).also { intent ->
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Unbind from the service
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    private fun startLocationService() {
        val intent = Intent(this, InferenceFrontService::class.java).apply {
            action = InferenceFrontService.ACTION_START
        }
        // Use startForegroundService for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Bind to the service to get updates
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopLocationService() {
        val intent = Intent(this, InferenceFrontService::class.java).apply {
            action = InferenceFrontService.ACTION_STOP
        }
        startService(intent) // Send stop command
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        serviceState = ServiceState() // Reset UI immediately
    }


    @RequiresApi(Build.VERSION_CODES.TIRAMISU) // Use Tiramisu for permissions
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Request all permissions at once
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
                    this@NewMainActivity.dataStore.data.collect { preferences ->
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
                                context = this@NewMainActivity,
                                navController = navController,
                                //ViewModel
                                mapViewModel = mapViewModel,
                                //UI State
                                jDMode = true,
                                isSampling = ,
                                isLocatingStarted=,
                                isLoadingStarted=,
                                isImuEnabled=,
                                isMyStepDetectorEnabled=,
                                //Sampling Data
                                yaw=,
                                pitch=,
                                roll=,
                                numOfLabelSampling=, // Start from 0
                                wifiScanningInfo=,
                                wifiSamplingCycles=,
                                sensorSamplingCycles=,
                                saveDirectory=,
                                isCollectTraining=,
                                //Location Data
                                userPositionInMeters=, // User's physical location (in meters)
                                userHeading=, // User orientation Angle (0-360)
                                waypoints=,
                                imuOffset=,
                                targetOffset=,
                                //Sampling Function
                                updateWifiSamplingCycles={ newSamplingCycles->
                                    //更新WiFi扫描频率
                                    //TODO
                                },
                                updateSensorSamplingCycles={ SensorSamplingCycles ->
                                    //更新传感器扫描频率
                                    //TODO
                                },
                                updateSaveDirectory={ newSaveDirectory ->
                                    //更新保存文件名
                                    //TODO
                                },
                                updateIsCollectTraining={ IsCollectTraining->
                                    //设置是/否将单词采集归类为Training Data
                                    //TODO
                                },
                                onStartSamplingButtonClicked={labelData, numOfLabelToSample, startScanningTime ->
                                    if (numOfLabelToSample == null) {
                                        //无标签数据采集逻辑
                                        //TODO
                                    } else {
                                        //有标签数据采集逻辑
                                        labelPoint=inferenceFrontService.serviceState.waypints[numOfLabelToSample]
                                        //TODO
                                    }
                                },
                                onStopSamplingButtonClicked={
                                    //停止采集数据逻辑
                                    //TODO
                                },
                                //Inference Function
                                startLocating={
                                    //开始定位逻辑
                                    //TODO
                                },
                                endLocating={
                                    //结束定位逻辑
                                    //TODO
                                },
                                refreshLocation={
                                    //刷新定位点逻辑
                                    //TODO
                                },
                                enableImu={
                                    //开启IMU传感器逻辑
                                    //TODO
                                },
                                disableImu={
                                    //关闭IMU传感器逻辑
                                    //TODO
                                },
                                enableMyStepDetector={
                                    //开启自己的Step Counter传感器逻辑
                                    //TODO
                                },
                                disableMyStepDetector={
                                    //关闭自己的Step Counter传感器逻辑
                                    //TODO
                                }
                            )
                        }
                        composable(MainActivityDestinations.Settings.route) {
                            SettingScreen(
                                context = this@NewMainActivity,
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
                                url = url,
                                azimuthOffset = azimuthOffset,
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
                                },
                                updateUrl = { newUrl ->
                                    url = newUrl
                                },
                                updateAzimuthOffset = {newAzimuthOffset ->
                                    azimuthOffset = newAzimuthOffset
                                }
                            )
                        }
                        composable(MainActivityDestinations.MapChoosing.route) {
                            MapChoosingScreen(
                                context = this@NewMainActivity,
                                navController = navController,
                                mapViewModel = mapViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}