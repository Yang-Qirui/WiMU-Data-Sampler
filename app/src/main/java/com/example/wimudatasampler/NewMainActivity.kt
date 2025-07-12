package com.example.wimudatasampler

import android.Manifest
import dagger.hilt.android.AndroidEntryPoint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wimudatasampler.Pages.MainScreen
import com.example.wimudatasampler.Pages.MapChoosingScreen
import com.example.wimudatasampler.Pages.SettingScreen
import com.example.wimudatasampler.navigation.MainActivityDestinations
import com.example.wimudatasampler.ui.theme.WiMUTheme
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NewMainActivity : ComponentActivity() {

    private val mapViewModel: MapViewModel by viewModels()

    // Service connection
    private var frontService: FrontService? = null
    private var isBound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as FrontService.LocationBinder
            frontService = binder.getService()
            isBound = true
            // Start collecting state updates from the service
            lifecycleScope.launch {
                frontService?.serviceState?.collect { newState ->
                    // Update UI state based on service state
                    serviceState = newState
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            frontService = null
        }
    }

    // UI State, now driven by the service
    private var serviceState by mutableStateOf(ServiceState())

    // Permissions logic remains in the Activity
    @RequiresApi(Build.VERSION_CODES.O) // Changed to Tiramisu for POST_NOTIFICATIONS
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // 检查关键的位置权限是否被授予
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            if (fineLocationGranted || coarseLocationGranted) {
                // 只有在用户授予位置权限后，才启动服务
                Log.i("Permission", "Location permission granted. Starting service.")
                startAndBindToFrontService()
            } else {
                // 用户拒绝了关键权限，不能启动服务
                Log.e("Permission", "Location permission denied. Cannot start service.")
                Toast.makeText(this, "Location permission is required to run the service.", Toast.LENGTH_LONG).show()
                // 你也可以在这里选择关闭应用或显示一个错误页面
            }
        }

    private var currentRequestedPermission: String? = null

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestRequiredPermissions() {
        // 创建一个基础权限列表
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        // 根据 SDK 版本，条件性地添加新权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 检查哪些权限是尚未被授予的
        val permissionsToAsk = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToAsk.isEmpty()) {
            // 所有需要的权限已经都被授予了
            Log.i("Permission", "All required permissions are already granted.")
            // 直接启动服务
            startAndBindToFrontService()
        } else {
            // 如果有尚未授予的权限，则发起请求
            Log.i("Permission", "Requesting permissions: ${permissionsToAsk.joinToString()}")
            requestPermissionLauncher.launch(permissionsToAsk)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private val permissionsToRequest =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACTIVITY_RECOGNITION,
            )
        } else {
            mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            )
        }

    override fun onStart() {
        super.onStart()
        // Bind to the service if it's running
        if (FrontService.isServiceRunning) {
            val success = bindToFrontService()
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

    private fun bindToFrontService():Boolean {
        Intent(this, FrontService::class.java).also { intent ->
            val success = bindService(intent, connection, 0)
            return success
        }
    }

    private fun startAndBindToFrontService() {
        // This function ensures the service is started in the foreground
        // and then binds to it.
        val intent = Intent(this, FrontService::class.java).apply {
            action = FrontService.ACTION_START
        }

        // Start the service in foreground mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Bind to the service
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindAndStopLocationService() {
        val intent = Intent(this, FrontService::class.java).apply {
            action = FrontService.ACTION_STOP
        }
        startService(intent) // Send stop command
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        serviceState = ServiceState() // Reset UI immediately
    }


    @RequiresApi(Build.VERSION_CODES.P) // Use Tiramisu for permissions
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Request all permissions at once
        requestRequiredPermissions()
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
                                jDMode = false,
                                isCollectTraining = serviceState.isCollectTraining,
                                isSampling = serviceState.isSampling,
                                isLocatingStarted = serviceState.isLocatingStarted,
                                isLoadingStarted = serviceState.isLoadingStarted,
                                isImuEnabled = serviceState.isImuEnabled,
                                isMyStepDetectorEnabled = serviceState.isMyStepDetectorEnabled,
                                //Sampling Data
                                yaw = serviceState.yaw,
                                pitch = serviceState.pitch,
                                roll = serviceState.roll,
                                numOfLabelSampling = serviceState.numOfLabelSampling, // Start from 0
                                wifiScanningInfo = serviceState.wifiScanningInfo,
                                wifiSamplingCycles = serviceState.wifiSamplingCycles,
                                sensorSamplingCycles = serviceState.sensorSamplingCycles,
                                saveDirectory = serviceState.saveDirectory,
                                //Location Data
                                userHeading = serviceState.userHeading, // User orientation Angle (0-360)
                                waypoints = serviceState.waypoints,
                                imuOffset = serviceState.imuOffset,
                                targetOffset = serviceState.targetOffset,// User's physical location (in meters)
                                //Sampling Function
                                updateWifiSamplingCycles = { newSamplingCycles ->
                                    //更新WiFi扫描频率
                                    frontService?.updateWifiSamplingCycles(newValue = newSamplingCycles)
                                },
                                updateSensorSamplingCycles = { newSensorSamplingCycles ->
                                    //更新传感器扫描频率
                                    frontService?.updateSensorSamplingCycles(newValue = newSensorSamplingCycles)
                                },
                                updateSaveDirectory = { newSaveDirectory ->
                                    //更新保存文件名
                                    frontService?.updateSaveDirectory(newValue = newSaveDirectory)
                                },
                                updateIsCollectTraining = { isCollectTraining ->
                                    //设置是/否将单词采集归类为Training Data
                                    frontService?.updateIsCollectTraining(newValue = isCollectTraining)
                                },
                                onStartSamplingButtonClicked = { indexOfLabelToSample, startScanningTime ->
                                    indexOfLabelToSample?.let { index ->
                                        // 有标签数据采集逻辑
                                        val labelPoint = serviceState.waypoints[index]
                                        frontService?.startCollectingLabelData(indexOfLabel= indexOfLabelToSample, labelPoint = labelPoint, startTimestamp = startScanningTime)
                                    } ?: run {
                                        // 无标签数据采集逻辑
                                        frontService?.startCollectingUnLabelData(startTimestamp = startScanningTime)
                                    }
                                },
                                onStopSamplingButtonClicked={
                                    //停止采集数据逻辑
                                    frontService?.stopCollectingData()
                                },
                                //Inference Function
                                startLocating={
                                    //开始定位逻辑
                                    frontService?.startLocating()
                                },
                                endLocating={
                                    //结束定位逻辑
                                    frontService?.stopLocating()
                                },
                                refreshLocation={
                                    //刷新定位点逻辑
                                    frontService?.refreshLocating()
                                },
                                enableImu={
                                    //开启IMU传感器逻辑
                                    frontService?.enableImuSensor()
                                },
                                disableImu={
                                    //关闭IMU传感器逻辑
                                    frontService?.disableImuSensor()
                                },
                                enableMyStepDetector={
                                    //开启自己的Step Counter传感器逻辑
                                    frontService?.enableOwnStepCounter()
                                },
                                disableMyStepDetector={
                                    //关闭自己的Step Counter传感器逻辑
                                    frontService?.disableOwnStepCounter()
                                }
                            )
                        }
                        composable(MainActivityDestinations.Settings.route) {
                            frontService?.let { service ->
                                SettingScreen(
                                    context = this@NewMainActivity,
                                    navController = navController,
                                    stride = service.stride,
                                    beta = service.beta,
                                    initialState = service.initialState,
                                    initialCovariance = service.initialCovariance,
                                    matrixQ = service.matrixQ,
                                    matrixR = service.matrixR,
                                    matrixRPowOne = service.matrixRPowOne,
                                    matrixRPowTwo = service.matrixRPowTwo,
                                    sysNoise = service.sysNoise,
                                    obsNoise = service.obsNoise,
                                    period = service.period,
                                    url = service.url,
                                    azimuthOffset = service.azimuthOffset,
                                    updateStride = { newStride ->
                                        service.stride = newStride
                                    },
                                    updateBeta = { newBeta ->
                                        service.beta = newBeta
                                    },
                                    updateInitialState = { newInitialState ->
                                        service.initialState = newInitialState
                                    },
                                    updateInitialCovariance = { newInitialCovariance ->
                                        service.initialCovariance = newInitialCovariance
                                    },
                                    updateMatrixQ = { newMatrixQ ->
                                        service.matrixQ = newMatrixQ
                                    },
                                    updateMatrixR = { newMatrixR ->
                                        service.matrixR = newMatrixR
                                    },
                                    updateMatrixRPowOne = { newMatrixRPowOne ->
                                        service.matrixRPowOne = newMatrixRPowOne
                                    },
                                    updateMatrixRPowTwo = { newMatrixRPowTwo ->
                                        service.matrixRPowTwo = newMatrixRPowTwo
                                    },
                                    updateFullMatrixR = { newFullMatrixR ->
                                        service.fullMatrixR = newFullMatrixR
                                    },
                                    updateSysNoise = { newSysNoise ->
                                        service.sysNoise = newSysNoise
                                    },
                                    updateObsNoise = { newObsNoise ->
                                        service.obsNoise = newObsNoise
                                    },
                                    updatePeriod = { newPeriod ->
                                        service.period = newPeriod
                                    },
                                    updateUrl = { newUrl ->
                                        service.url = newUrl
                                    },
                                    updateAzimuthOffset = { newAzimuthOffset ->
                                        service.azimuthOffset = newAzimuthOffset
                                    }
                                )
                            }
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