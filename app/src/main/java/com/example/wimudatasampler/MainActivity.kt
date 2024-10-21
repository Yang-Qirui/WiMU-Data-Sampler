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


class MainActivity : ComponentActivity(), SensorUtils.SensorDataListener {
    private lateinit var motionSensorManager: SensorUtils
    private lateinit var wifiManager: WifiManager
    private lateinit var wifiScanReceiver: BroadcastReceiver
    private lateinit var timer: TimerUtils
    private var startSamplingTime: String = ""
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
                Scaffold (topBar = { TopAppBar(title = { Text(text = "WiMU Data Sampler") }, colors = TopAppBarColors(containerColor = Color.DarkGray, titleContentColor = Color.White, actionIconContentColor = Color.White, navigationIconContentColor = Color.White, scrolledContainerColor = Color.Gray))}) {
                     innerPadding ->
                        SampleWidget(
                            context = this,
                            sensorManager = motionSensorManager,
                            wifiManager = wifiManager,
                            padding = innerPadding,
                            timer = timer,
                            setStartSamplingTime = {
                                time -> startSamplingTime = time
                            }
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
        val dir = File(this.getExternalFilesDir(null), startSamplingTime)
        if (!dir.exists()){
            dir.mkdirs()
        }
        val file = File(dir, "rot.txt") // 保存到外部存储
        try {
            val writer = FileWriter(file, true) // 追加模式写入
            writer.append("${System.currentTimeMillis()} ${rotationVector[0]} ${rotationVector[1]} ${rotationVector[2]} ${rotationVector[3]}\n")
            writer.flush()
            writer.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun onStepCountChanged(stepCount: Float) {
        val dir = File(this.getExternalFilesDir(null), startSamplingTime)
        if (!dir.exists()){
            dir.mkdirs()
        }
        val file = File(dir, "step.txt") // 保存到外部存储
        try {
            val writer = FileWriter(file, true) // 追加模式写入
            writer.append("${System.currentTimeMillis()} $stepCount")
            writer.flush()
            writer.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


}

@Composable
fun SampleWidget(context: SensorUtils.SensorDataListener, sensorManager: SensorUtils, wifiManager: WifiManager, padding: PaddingValues, timer: TimerUtils, setStartSamplingTime: (String) -> Unit) {
        var resultText by remember {
            mutableStateOf("Scanning Result: 0")
        }
        var freq by remember {
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
            Button(onClick = {
                timer.runEverySecondForMinute({wifiScan(wifiManager)}) {
                    count ->
                    resultText = "Valid Scanning Result: $count, Time period 60s.\n Recommended sampling frequency: ${60 / count} Hz"
                }
            }) {
                Text(text = "Test Wi-Fi Sampling Frequency")
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextField(
                value = freq,
                onValueChange = { newText ->
                    // Only allow valid integer values greater than 0
                    if (newText.isEmpty() || newText.toIntOrNull()?.let { it > 0 } == true) {
                        freq = newText
                    }
                },
                label = { Text("Enter frequency") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
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
                        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(currentTimeMillis)
                        val frequency = freq.toInt()
                        setStartSamplingTime(currentTime)
                        timer.runTaskAtFrequency({ wifiScan(wifiManager) }, frequency, currentTime,
                            "WiFi.txt"
                        ) { successCount ->
                            Log.d("Finished", "Sampling finished, successful scans: $successCount")
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

    interface SensorDataListener {
        fun onRotationVectorChanged(rotationVector: FloatArray)
        fun onStepCountChanged(stepCount: Float)
    }
    private var sensorDataListener: SensorDataListener? = null

    fun startMonitoring(listener: SensorDataListener) {
        this.sensorDataListener = listener
        val rotationSuccess = sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_NORMAL)
        val stepSuccess = sensorManager.registerListener(this, stepCountSensor, SensorManager.SENSOR_DELAY_NORMAL)
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
                sensorDataListener?.onRotationVectorChanged(event.values)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                sensorDataListener?.onStepCountChanged(event.values[0])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("Sensor Accuracy Changed", "Accuracy changed to: $accuracy")
    }
}

@Suppress("DEPRECATION")
class TimerUtils(private val context: Context) {
    private val handler = Handler()
    private var elapsedTime = 0
    private var successCounter = 0
    private var isRunning = true
    private var failedHappened = false

    fun runEverySecondForMinute(task: () -> Pair<String, Boolean>, onComplete: (Int) -> Unit) {
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

    fun runTaskAtFrequency(task: () -> Pair<String, Boolean>, frequency: Int, timestamp: String, fileName: String, onComplete: (Int) -> Unit) {
        elapsedTime = 0
        successCounter = 0
        isRunning = true  // 开始任务时将标志设为true

        val dir = File(context.getExternalFilesDir(null), timestamp)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, fileName) // 保存到外部存储
        Log.i("SAVE_PATH", file.toString())

        val runnable: Runnable = object : Runnable {
            override fun run() {
                if (!isRunning) return  // 检查是否需要停止任务

                val (output, success) = task() // 获取任务的String和Boolean输出
                if (success) {
                    successCounter += 1
                    // 将执行结果写入文件
                    try {
                        val writer = FileWriter(file, true) // 追加模式写入
                        writer.append("Timestamp ${System.currentTimeMillis()}, $output\n")
                        writer.flush()
                        writer.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }

                elapsedTime += frequency
                if (isRunning) {
                    handler.postDelayed(this, (frequency * 1000).toLong()) // 按照频率执行任务
                } else {
                    onComplete(successCounter) // 当任务完成或停止时，调用onComplete回调
                }
            }
        }

        handler.post(runnable)
    }

    // 提供停止任务的方法
    fun stopTask() {
        isRunning = false  // 将标志设为false，停止任务
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
