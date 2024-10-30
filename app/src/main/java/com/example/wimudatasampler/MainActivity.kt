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
import android.os.Handler
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wimudatasampler.ui.theme.WiMUDataSamplerTheme
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.mutableFloatStateOf


class MainActivity : ComponentActivity(), SensorUtils.SensorDataListener {
    private lateinit var motionSensorManager: SensorUtils
    private lateinit var wifiManager: WifiManager
    private lateinit var wifiScanReceiver: BroadcastReceiver
    private lateinit var timer: TimerUtils
    private var startSamplingTime: String = ""
    private var lastRotationVector: FloatArray? = null
    private var lastStepCount: Float? = null
    private var yaw by mutableFloatStateOf(0f)
    private var pitch by mutableFloatStateOf(0f)
    private var roll by mutableStateOf(0f)
    private var isMonitoringAngles by mutableStateOf(false)
    private var showStepCountDialog by mutableStateOf(false)  // 控制弹窗显示状态
    private var stepCount by mutableFloatStateOf(0f)  // 保存步数值


    @RequiresApi(Build.VERSION_CODES.Q)
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()){
        isGranted: Boolean ->
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
        Manifest.permission.ACTIVITY_RECOGNITION
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNextPermission()

        enableEdgeToEdge()
        setContent {
            WiMUDataSamplerTheme {
                Scaffold (topBar = { TopAppBar(title = { Text(text = "WiMU Data Sampler") }, colors = TopAppBarColors(containerColor = Color.DarkGray, titleContentColor = Color.White, actionIconContentColor = Color.White, navigationIconContentColor = Color.White, scrolledContainerColor = Color.Gray))}) { innerPadding ->
                    SampleWidget(
                        context = this,
                        sensorManager = motionSensorManager,
                        wifiManager = wifiManager,
                        padding = innerPadding,
                        timer = timer,
                        setStartSamplingTime = { time ->
                            startSamplingTime = time
                        },
                        yaw = yaw,
                        pitch = pitch,
                        roll = roll,
                        isMonitoringAngles = isMonitoringAngles,
                        toggleMonitoringAngles = { toggleAngleMonitoring() }
                    )
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

        timer = TimerUtils(this)
        motionSensorManager = SensorUtils(this)
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

    override fun onStepCountChanged(stepCount: Float) {
        lastStepCount = stepCount
        this.stepCount = stepCount  // 更新步数值
        showStepCountDialog = true  // 显示弹窗
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
fun SampleWidget(context: SensorUtils.SensorDataListener, sensorManager: SensorUtils, wifiManager: WifiManager, padding: PaddingValues, timer: TimerUtils, setStartSamplingTime: (String) -> Unit, yaw: Float, pitch: Float, roll: Float, isMonitoringAngles: Boolean, toggleMonitoringAngles: () -> Unit) {
        var resultText by remember {
            mutableStateOf("Scanning Result: 0")
        }
        var wifiFreq by remember {
            mutableStateOf("")
        }
        var sensorFreq by remember {
            mutableStateOf("")
        }
        var isSampling by remember {
            mutableStateOf(false)
        }

        Column (modifier = Modifier
            .padding(padding)
            .fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text = resultText)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Yaw: ${yaw.toInt()}°")
            Text(text = "Pitch: ${pitch.toInt()}°")
            Text(text = "Roll: ${roll.toInt()}°")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { toggleMonitoringAngles() }, colors = if (isMonitoringAngles) {
                ButtonDefaults.buttonColors(containerColor = Color.Red )
            } else {
                ButtonDefaults.buttonColors()
            }) {
                Text(text = if (isMonitoringAngles) "Stop Monitoring Angles" else "Start Monitoring Angles")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                timer.runEverySecondForMinute({wifiScan(wifiManager)}) {
                    count ->
                    resultText = "Valid Scanning Result: $count, Time period 60s.\n Recommended sampling cycle: ${60 / count} s"
                }
            }) {
                Text(text = "Test Wi-Fi Sampling Frequency")
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextField(
                value = wifiFreq,
                onValueChange = { newText ->
                    wifiFreq = newText
                },
                label = { Text("Enter wifi sampling cycle (s)") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
            TextField(
                value = sensorFreq,
                onValueChange = { newText ->
                    sensorFreq = newText
                },
                label = { Text("Enter sensor sampling cycle (s)") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (!isSampling) {
                        // 开始任务逻辑
                        val currentTimeMillis = System.currentTimeMillis()
                        val currentTime = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(currentTimeMillis)
                        val wifiFrequency = wifiFreq.toDouble()
                        val sensorFrequency = sensorFreq.toDouble()
                        setStartSamplingTime(currentTime)
                        timer.runSensorTaskAtFrequency(sensorManager, sensorFrequency, currentTime) {
                            Log.d("Sensor Finished", "Sampling finished, successful samples: $it")
                        }
                        timer.runWifiTaskAtFrequency(wifiManager, wifiFrequency, currentTime) {
                            Log.d("WiFi Finished", "Wi-Fi sampling finished, successful samples: $it")
                        }
                        sensorManager.startMonitoring(context)
                        isSampling = true  // 切换为正在采样的状态
                    } else {
                        // 停止任务逻辑
                        timer.stopTask()
                        sensorManager.stopMonitoring()
                        isSampling = false  // 切换为停止采样状态
                    }
                },
                 colors = if (isSampling) {
                     ButtonDefaults.buttonColors(containerColor = Color.Red )
                 } else {
                     ButtonDefaults.buttonColors()
                 }
            ) {
                // 根据当前状态设置按钮文本
                Text(text = if (isSampling) "Stop Sampling" else "Start Sampling")
            }
        }
}

class SensorUtils(context: Context): SensorEventListener {
    private var sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var stepCountSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private var lastRotationVector: FloatArray? = null
    private var lastStepCount: Float? = null

    interface SensorDataListener {
        fun onRotationVectorChanged(rotationVector: FloatArray)
        fun onStepCountChanged(stepCount: Float)
    }
    private var sensorDataListener: SensorDataListener? = null

    fun startMonitoring(listener: SensorDataListener) {
        this.sensorDataListener = listener
        val rotationSuccess = sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_FASTEST)
        val stepSuccess = sensorManager.registerListener(this, stepCountSensor, SensorManager.SENSOR_DELAY_FASTEST)
        if (!rotationSuccess) {
            Log.e("SensorRegister", "Failed to register rotation vector sensor listener")
        }
        if (!stepSuccess) {
            Log.e("SensorRegister", "Failed to register step count sensor listener")
        }
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                lastRotationVector = event.values
                sensorDataListener?.onRotationVectorChanged(event.values)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                lastStepCount = event.values[0]
                sensorDataListener?.onStepCountChanged(event.values[0])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("Sensor Accuracy Changed", "Accuracy changed to: $accuracy")
    }

    fun getLastRotationVector(): FloatArray? = lastRotationVector
    fun getLastStepCount(): Float? = lastStepCount

}

@Suppress("DEPRECATION")
class TimerUtils(private val context: Context) {
    private val handler = Handler()
    private var elapsedTime = 0
    private var isSensorTaskRunning = false
    private var failedHappened = false
    private var isWifiTaskRunning = false


    fun runEverySecondForMinute(task: () -> Pair<String, Boolean>, onComplete: (Int) -> Unit) {
        var successCounter = 0
        elapsedTime = 0
        val runnable: Runnable = object : Runnable {
            override fun run() {
                val (_, success) = task()
                if (success) {
                    successCounter += 1
                } else {
                    failedHappened = true
                }
                elapsedTime += 1

                if (success and failedHappened) {
                    onComplete(successCounter)
                } else {
                    handler.postDelayed(this, 1000) // 每隔1秒重复运行
                }
            }
        }

        handler.post(runnable)
    }

    fun runSensorTaskAtFrequency(
        sensorManager: SensorUtils,
        frequency: Double,
        timestamp: String,
        onComplete: (Int) -> Unit
    ) {
        var successCounter = 0
        val dir = File(context.getExternalFilesDir(null), timestamp)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val stepFile = File(dir, "step.txt")
        val rotationFile = File(dir, "rotation.txt")
        val yawFile = File(dir, "yaw.txt")


        val runnable: Runnable = object : Runnable {
            override fun run() {
                if (!isSensorTaskRunning) return

                // Use the last known sensor data, or fallback to default if not available
                val rotationVector = sensorManager.getLastRotationVector() ?: floatArrayOf(0f, 0f, 0f, 0f)
                val stepCount = sensorManager.getLastStepCount() ?: 0f

                val currentTime = System.currentTimeMillis()
                try {
                    val rotationWriter = FileWriter(rotationFile, true)
                    rotationWriter.append("$currentTime ${rotationVector.joinToString(" ")}\n")
                    rotationWriter.flush()
                    rotationWriter.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

                try {
                    // Write rotation vector data
                    val yawWriter = FileWriter(yawFile, true)
                    // Calculate and write yaw data
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
                    val orientations = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientations)
                    val yaw = Math.toDegrees(orientations[0].toDouble()).toFloat()
                    yawWriter.append("$currentTime $yaw\n")
                    yawWriter.flush()
                    yawWriter.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

                // Write the step count data
                try {
                    val stepWriter = FileWriter(stepFile, true)
                    stepWriter.append("$currentTime $stepCount\n")
                    stepWriter.flush()
                    stepWriter.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

                successCounter++

                if (isSensorTaskRunning) {
                    handler.postDelayed(this, (frequency * 1000).toLong())
                } else {
                    onComplete(successCounter)
                }
            }
        }

        isSensorTaskRunning = true
        handler.post(runnable)
    }

    fun runWifiTaskAtFrequency(
        wifiManager: WifiManager,
        frequencyY: Double, // Wi-Fi 采集频率 (秒)
        timestamp: String,
        onComplete: (Int) -> Unit
    ) {
        var successCounter = 0
        val dir = File(context.getExternalFilesDir(null), timestamp)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val wifiFile = File(dir, "wifi.txt")

        val wifiRunnable: Runnable = object : Runnable {
            override fun run() {
                if (!isWifiTaskRunning) return

                // 执行 Wi-Fi 扫描并写入结果
                val (wifiResults, success) = wifiScan(wifiManager)
                if (success) {
                    successCounter++
                    try {
                        val wifiWriter = FileWriter(wifiFile, true)
                        wifiWriter.append("$wifiResults\n")
                        wifiWriter.flush()
                        wifiWriter.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }

                if (isWifiTaskRunning) {
                    handler.postDelayed(this, (frequencyY * 1000).toLong())  // 按照频率Y采集
                } else {
                    onComplete(successCounter)
                }
            }
        }

        isWifiTaskRunning = true
        handler.post(wifiRunnable)
    }

    // 提供停止任务的方法
    fun stopTask() {
        isSensorTaskRunning = false  // 将标志设为false，停止任务
        isWifiTaskRunning = false
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun wifiScan(wifiManager: WifiManager): Pair<String, Boolean> {
    val success = wifiManager.startScan()
    if (success) {
        val scanResults = wifiManager.scanResults
        val resultList = scanResults.map { scanResult ->
            "${System.currentTimeMillis()} ${scanResult.SSID} ${scanResult.BSSID} ${scanResult.frequency} ${scanResult.level}"
        }

        val resultString = resultList.joinToString("\n")  // 将每个结果拼接为字符串，使用换行分隔
        Log.d("OUT", resultString)
        return Pair(resultString, true)
    } else {
        Log.e("ERR", "Scanning failed!")
        return Pair("", false)
    }
}
