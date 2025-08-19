package com.example.wimudatasampler

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import dagger.hilt.android.AndroidEntryPoint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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

    // --- NEW: State to control the location disabled dialog ---
    private var showLocationDisabledDialog by mutableStateOf(false)
    private var showBluetoothDisabledDialog by mutableStateOf(false)

    // --- NEW: BroadcastReceiver to listen for location provider changes ---
    private val locationSwitchStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (LocationManager.PROVIDERS_CHANGED_ACTION == intent.action) {
                // Location state has changed, re-check and update the dialog state
                checkLocationAndShowDialog()
            }
        }
    }

    private val bluetoothSwitchStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED == intent.action) {
                // Bluetooth state has changed, re-check and update the dialog state
                checkBluetoothAndShowDialog()
            }
        }
    }

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
            }
        }
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestRequiredPermissions() {
        // 基础权限列表
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
        // --- Conditional Bluetooth permissions for Android 12+ ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
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

    // --- MODIFIED: onStart lifecycle method ---
    override fun onStart() {
        super.onStart()
        // Bind to the service if it's running
        if (FrontService.isServiceRunning) {
            bindToFrontService()
        }

        // NEW: Register the receiver and perform the initial check
        registerReceiver(locationSwitchStateReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        // --- Register the Bluetooth receiver ---
        registerReceiver(bluetoothSwitchStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        // Perform initial checks
        checkLocationAndShowDialog()
        // --- Perform initial Bluetooth check ---
        checkBluetoothAndShowDialog()
    }

    // --- MODIFIED: onStop lifecycle method ---
    override fun onStop() {
        super.onStop()
        // Unbind from the service
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        // NEW: Unregister the receiver to prevent memory leaks
        unregisterReceiver(locationSwitchStateReceiver)
        unregisterReceiver(bluetoothSwitchStateReceiver)
    }

    // --- NEW: Helper function to check if location services are enabled ---
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // --- NEW: Helper function to show dialog if location is disabled ---
    private fun checkLocationAndShowDialog() {
        showLocationDisabledDialog = !isLocationEnabled()
    }

    // --- NEW: Helper function to open system location settings ---
    private fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        startActivity(intent)
    }

    // --- Helper functions for Bluetooth ---
    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter ?: return false // Device doesn't support Bluetooth
        return bluetoothAdapter.isEnabled
    }
    private fun checkBluetoothAndShowDialog() {
        showBluetoothDisabledDialog = !isBluetoothEnabled()
    }
    private fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        startActivity(intent)
    }

    private fun bindToFrontService():Boolean {
        Intent(this, FrontService::class.java).also { intent ->
            return bindService(intent, connection, 0)
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

                // --- NEW: Conditionally display the AlertDialog ---
                if (showLocationDisabledDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            // We don't want the user to dismiss this dialog
                            // as the app is not functional without location.
                        },
                        title = { Text(text = "Location service has been turned off") },
                        text = { Text(text = "This application requires the location service to be enabled in order to function properly. Please enable it in the system settings.") },
                        confirmButton = {
                            Button(onClick = { openLocationSettings() }) {
                                Text("Go to Settings")
                            }
                        }
                    )
                }

                if (showBluetoothDisabledDialog) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(text = "Bluetooth is off") },
                        text = { Text(text = "This application requires Bluetooth to be enabled for data collection. Please enable it in system settings.") },
                        confirmButton = {
                            Button(onClick = { openBluetoothSettings() }) {
                                Text("Go to Settings")
                            }
                        }
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface
                ) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = MainActivityDestinations.Main.route
                    ) {
                        // ... (Rest of your NavHost code remains unchanged)
                        composable(MainActivityDestinations.Main.route) {
                            MainScreen(
                                context = this@MainActivity,
                                navController = navController,
                                //ViewModel
                                mapViewModel = mapViewModel,
                                //UI State
                                jDMode = Config.JD_MODE,
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
                                    context = this@MainActivity,
                                    navController = navController,
                                    isReRegistering = serviceState.isReRegistering,
                                    useBleLocating = serviceState.useBleLocating,
                                    isBluetoothTimeWindowEnabled = serviceState.isBluetoothTimeWindowEnabled,
                                    bluetoothTimeWindowSeconds = serviceState.bluetoothTimeWindowSeconds,
                                    warehouseName = service.warehouseName,
                                    stride = service.stride,
                                    beta = service.beta,
                                    sysNoise = service.sysNoise,
                                    obsNoise = service.obsNoise,
                                    period = service.period,
                                    url = service.url,
                                    mqttServerUrl = service.mqttServerUrl,
                                    apiBaseUrl = service.apiBaseUrl,
                                    azimuthOffset = service.azimuthOffset,
                                    reRegisterMqttClient = {
                                        frontService?.reRegisterMqttClient()
                                    },
                                    updateUseBleLocating = { newUseBleLocating ->
                                        frontService?.updateUseBleLocating(newValue = newUseBleLocating)
                                    },
                                    updateIsBluetoothTimeWindowEnabled = { newIsBluetoothTimeWindowEnabled ->
                                        frontService?.updateIsBluetoothTimeWindowEnabled(newValue = newIsBluetoothTimeWindowEnabled)
                                    },
                                    updateBluetoothTimeWindowSeconds = { newBluetoothTimeWindowSeconds ->
                                        frontService?.updateBluetoothTimeWindowSeconds(newValue = newBluetoothTimeWindowSeconds)
                                    },
                                    updateWarehouseName = { newWarehouseName ->
                                        frontService?.reRegisterMqttClient()
                                    },
                                    updateStride = { newStride ->
                                        service.stride = newStride
                                    },
                                    updateBeta = { newBeta ->
                                        service.beta = newBeta
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
                                    updateMqttServerUrl = { newUrl ->
                                        service.mqttServerUrl = newUrl
                                    },
                                    updateApiBaseUrl = { newUrl ->
                                        service.apiBaseUrl = newUrl
                                    },
                                    updateAzimuthOffset = { newAzimuthOffset ->
                                        service.azimuthOffset = newAzimuthOffset
                                    }
                                )
                            }
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
    }
}