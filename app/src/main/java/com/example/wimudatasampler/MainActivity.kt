package com.example.wimudatasampler

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
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
import android.os.Environment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableFloatStateOf
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.navigation.NavController
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Upcoming
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.Icon
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toOffset
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.math.sqrt


class MainActivity : ComponentActivity(), SensorUtils.SensorDataListener {
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
    private var stride by mutableFloatStateOf(0.4f)
    private var targetOffset by mutableStateOf(Offset.Zero)
    private var beta by mutableFloatStateOf(1.0f)
    private val initialState = doubleArrayOf(0.0, 0.0)
    private val initialCovariance = arrayOf(
        doubleArrayOf(5.0, 0.0),
        doubleArrayOf(0.0, 1.0)
    )
    val Q = arrayOf(      // 过程噪声（预测误差）
        doubleArrayOf(0.05, 0.0),
        doubleArrayOf(0.0, 0.05)
    )
    val R = arrayOf(      // 观测噪声
        doubleArrayOf(4.65.pow(2), 0.0),
        doubleArrayOf(0.0, 1.75.pow(2))
    )
    private val filter = KalmanFilter(initialState, initialCovariance, Q, R)

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
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS
    )

    private val scope = CoroutineScope(Dispatchers.IO)
    private val imuScope = CoroutineScope(Dispatchers.IO)
    private fun euclideanDistance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return sqrt((x2 - x1).pow(2) + (y2 - y1).pow(2))
    }
    private fun startFetching() {
        motionSensorManager.startMonitoring(this)
        var warmupCounter = 0
        scope.launch {
            while (true) {
                val (wifiResults, success) = wifiScan(wifiManager)
                if (success) {
                    Log.d("Map", "Wifi Scan Results")
//                    Log.d("BETA", beta.toString())
                    try {
                        val response = NetworkClient.fetchData(wifiResults)
                        Log.d("response", response.bodyAsText())
                        var coordinate: Coordinate?
                        try {
                            coordinate = Json.decodeFromString<Coordinate>(response.bodyAsText())
                        } catch (e:Exception) {
                            delay(5000)
                            continue
                        }
                        wifiOffset = Offset(coordinate.x, coordinate.y)

                        Log.d("wifioffset", wifiOffset.toString())
                        if ( warmupCounter > 2 ) {
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
                    }
                    catch (e: Exception) {
                        Log.e("Http exception", e.toString())
                    }
                }
                delay(5000)
            }
        }
    }

    private fun endFetching() {
        scope.cancel()
        imuScope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
    }

    private fun getBetaValue(): Float {
        return beta
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNextPermission()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge()
        setContent {
            WiMUDataSamplerTheme {
                // Main Content wrapped in Scaffold
                val navController = rememberNavController()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(text = "WiMU Demo") },
                            colors = TopAppBarColors(containerColor = Color.DarkGray,
                                                     titleContentColor = Color.White,
                                                     actionIconContentColor = Color.White,
                                                     navigationIconContentColor = Color.White,
                                                     scrolledContainerColor = Color.Gray)
                        )
                    },
                    bottomBar = { BottomNavigationBar(navController) }
                ) { innerPadding ->
                    NavHost(navController, startDestination = "sample", Modifier.padding(innerPadding)) {
                        composable("sample") {
                            SampleWidget(
                                context = this@MainActivity,  // Pass the context
                                sensorManager = motionSensorManager,
                                wifiManager = wifiManager,
                                padding = innerPadding,
                                timer = timer,
                                setStartSamplingTime = { time -> startSamplingTime = time },
                                yaw = yaw,
                                pitch = pitch,
                                roll = roll,
                                isMonitoringAngles = isMonitoringAngles,
                                toggleMonitoringAngles = { toggleAngleMonitoring() },
                                waypoints = waypoints,
                                changeBeta = {value -> beta = value},
                                getBeta = { getBetaValue() }
                            )
                        }
                        composable("inference") {
                            InferenceScreen(targetOffset = targetOffset, yaw = yaw, waypoints = waypoints, startFetching = { startFetching() }, endFetching = {endFetching()}, imuOffset = imuOffset, wifiOffset = wifiOffset)
                        }
                        composable("Track") {
                            TrackingScreen(context = this@MainActivity, waypoints = trackingWaypoints, sensorManager = motionSensorManager, wifiManager = wifiManager, timer = timer)
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
            val x = imuOffset!!.x - stride * cos(Math.toRadians(yaw.toDouble()).toFloat()) // north is negative axis
            val y = imuOffset!!.y - stride * sin(Math.toRadians(yaw.toDouble()).toFloat())
            imuOffset = Offset(x, y)
            filter.predict(0.49, Math.toRadians(yaw.toDouble()).toFloat())
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

class KalmanFilter(
    private var state: DoubleArray,       // 状态向量 [x, y]
    private var covariance: Array<DoubleArray>, // 协方差矩阵 2x2
    private val processNoise: Array<DoubleArray>, // 过程噪声 Q
    private val measurementNoise: Array<DoubleArray> // 观测噪声 R
) {
    fun setInit(doubles: DoubleArray) {
        state = doubles
    }
    /**
     * 高频预测步骤（通过步长和航向角更新）
     * @param stride 移动步长
     * @param yaw 航向角（弧度）
     */
    fun predict(stride: Double, yaw: Float) {
        // 计算运动增量
        val deltaX = stride * cos(yaw)
        val deltaY = stride * sin(yaw)

        // 更新状态估计
        state[0] -= deltaX
        state[1] -= deltaY

        // 更新协方差矩阵: P = P + Q
        covariance = matrixAdd(covariance, processNoise)
    }

    /**
     * 低频观测更新步骤
     * @param measurement 观测值 [x, y]
     */
    fun update(measurement: DoubleArray) {
        // 计算卡尔曼增益
        val S = matrixAdd(covariance, measurementNoise)
        val K = matrixMultiply(covariance, matrixInverse(S))

        // 更新状态估计
        val innovation = doubleArrayOf(
            measurement[0] - state[0],
            measurement[1] - state[1]
        )
        val correction = matrixVectorMultiply(K, innovation)
        state[0] += correction[0]
        state[1] += correction[1]

        // 更新协方差矩阵: P = (I - K) * P
        val identity = identityMatrix(2)
        covariance = matrixMultiply(matrixSubtract(identity, K), covariance)
    }

    // 矩阵加法
    private fun matrixAdd(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        return Array(2) { i ->
            DoubleArray(2) { j ->
                a[i][j] + b[i][j]
            }
        }
    }

    // 矩阵乘法
    private fun matrixMultiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        return Array(2) { i ->
            DoubleArray(2) { j ->
                (0 until 2).sumOf { k -> a[i][k] * b[k][j] }
            }
        }
    }

    // 矩阵求逆（仅限 2x2）
    private fun matrixInverse(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val det = matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]
        require(det != 0.0) { "Matrix is singular" }
        return Array(2) { i ->
            DoubleArray(2) { j ->
                when {
                    i == 0 && j == 0 -> matrix[1][1] / det
                    i == 0 && j == 1 -> -matrix[0][1] / det
                    i == 1 && j == 0 -> -matrix[1][0] / det
                    else -> matrix[0][0] / det
                }
            }
        }
    }

    // 矩阵向量乘法
    private fun matrixVectorMultiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        return DoubleArray(2) { i ->
            (0 until 2).sumOf { j -> matrix[i][j] * vector[j] }
        }
    }

    // 生成单位矩阵
    private fun identityMatrix(size: Int): Array<DoubleArray> {
        return Array(size) { i ->
            DoubleArray(size) { j ->
                if (i == j) 1.0 else 0.0
            }
        }
    }

    // 矩阵减法
    private fun matrixSubtract(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        return Array(2) { i ->
            DoubleArray(2) { j ->
                a[i][j] - b[i][j]
            }
        }
    }

    // 获取当前状态
    fun getState(): DoubleArray = state.copyOf()
}

@Serializable
data class WifiEntry(
    val timestamp: Long,
    val bssid: String,
    val ssid: String?,  // SSID可以为空
    val frequency: Int,
    val rssi: Int
)

@Serializable
data class Coordinate(
    val x: Float,
    val y: Float
)

object NetworkClient {
    private val client = HttpClient(CIO)

    fun parseWifiData(input: String): List<WifiEntry> {
        return input.lines().mapNotNull { line ->
            val parts = line.split(" ")
            if (parts.size >= 5) { // 确保有足够的部分
                try {
                    val timestamp = parts[0].toLong() // 第一个是时间戳
                    val bssid = parts[2] // 第三个是BSSID
                    val frequency = parts[3].toInt() // 第四个是频率
                    val rssi = parts[4].toInt() // 第五个是RSSI

                    // 将ssid设置为parts[1]，如果它为空，则为null
                    val ssid = if (parts[1].isEmpty()) null else parts[1]

                    WifiEntry(timestamp, bssid, ssid, frequency, rssi)
                } catch (e: Exception) {
                    null // 解析错误，返回null
                }
            } else {
                null // 如果部分不足，返回null
            }
        }
    }

    suspend fun fetchData(wifiResult: String): HttpResponse {
        val wifiEntries = parseWifiData(wifiResult.trimIndent())
        Log.d("monitor", wifiEntries.toString())
        return client.post("http://limcpu1.cse.ust.hk:7860/wimu/echo") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(wifiEntries))
        }
    }


}

// Navigation Bar for pages: Data Visualization and Data Collecting
@Composable
fun BottomNavigationBar(navController: NavController) {
    NavigationBar {
        NavigationBarItem(
            icon = { Icon( Icons.Rounded.Upcoming, contentDescription = "Sample") },
            label = { Text("Sample") },
            selected = false, // You can change this dynamically
            onClick = {
                navController.navigate("sample")
            }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Rounded.Bookmark, contentDescription = "Inference") },
            label = { Text("Inference") },
            selected = false, // You can change this dynamically
            onClick = {
                navController.navigate("inference")
            }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Rounded.AccessibilityNew, contentDescription = "Track") },
            label = { Text("Track") },
            selected = false, // You can change this dynamically
            onClick = {
                navController.navigate("track")
            }
        )
    }

}

@Composable
fun SampleWidget(context: SensorUtils.SensorDataListener,
                 sensorManager: SensorUtils,
                 wifiManager: WifiManager,
                 padding: PaddingValues,
                 timer: TimerUtils,
                 setStartSamplingTime: (String) -> Unit,
                 yaw: Float, pitch: Float, roll: Float,
                 isMonitoringAngles: Boolean,
                 toggleMonitoringAngles: () -> Unit,
                 waypoints: SnapshotStateList<Offset>,
                 changeBeta: (Float) -> Unit,
                 getBeta: () -> Float
                 ) {

        var wifiFreq by remember {
            mutableStateOf("15")
        }
        var sensorFreq by remember {
            mutableStateOf("0.05")
        }
        var isSampling by remember {
            mutableStateOf(false)
        }
        var dirName by remember {
            mutableStateOf("")
        }

        var selectorExpanded by remember { mutableStateOf(false) }
        var selectedValue by remember { mutableStateOf("") }
        val scope = CoroutineScope(Dispatchers.Main)

        Column (modifier = Modifier
            .padding(padding)
            .fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(text = "Beta: ${getBeta()}")
            Slider(
                value = getBeta(),
                onValueChange = { newValue ->
                    changeBeta(newValue)
                },
                valueRange = 0f..1f, // 定义值的范围
                steps = 19, // 如果想要均匀分布的10个步，设置步数为9
            )

            Spacer(modifier = Modifier.height(16.dp))
            // Select a waypoint to collect data
            Button(onClick = {
                selectorExpanded = true
            }) {
                if(selectedValue == "") {
                    Text("Collecting Unlabeled Data")
                }else{
                    Text("Collect Waypoint $selectedValue")
                }
            }
            DropdownMenu(expanded = selectorExpanded, onDismissRequest = { selectorExpanded = false }, Modifier.fillMaxWidth()) {
                DropdownMenuItem(text={Text("Unlabeled Data")},onClick = {
                    selectedValue = ""
                    selectorExpanded = false
                })
                for ((index, waypoint) in waypoints.withIndex()) {
                    DropdownMenuItem(text={Text("Waypoint ${index+1}: ${waypoint.x}, ${waypoint.y}")},onClick = {
                        selectedValue = "${index+1}"
                        selectorExpanded = false
                    })
                }
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
            TextField(
                value = dirName,
                onValueChange = { newText ->
                    dirName = newText
                },
                label = { Text("Enter the name of save directory") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
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
                        if(selectedValue != "") {
                            dirName = "Waypoint-${selectedValue}-$currentTime"
                        }
                        scope.launch {
                            timer.runSensorTaskAtFrequency(
                                sensorManager,
                                sensorFrequency,
                                currentTime,
                                dirName,
                                "point"
                            ) {
                                Log.d(
                                    "Sensor Finished",
                                    "Sampling finished, successful samples: $it"
                                )
                            }
                        }
                        if (selectedValue != ""){
                            val waypointPosition = waypoints[selectedValue.toInt() - 1]
                            scope.launch {
                                timer.runWifiTaskAtFrequency(
                                    wifiManager,
                                    wifiFrequency,
                                    currentTime,
                                    dirName,
                                    true,
                                    getWaypoint = { waypointPosition }) {
                                    Log.d(
                                        "WiFi Finished",
                                        "Wi-Fi sampling finished for waypoint $selectedValue, successful samples: $it"
                                    )
                                }
                            }
                        }else{
                            scope.launch {
                                timer.runWifiTaskAtFrequency(
                                    wifiManager,
                                    wifiFrequency,
                                    currentTime,
                                    dirName,
                                    false
                                ) {
                                    Log.d(
                                        "WiFi Finished",
                                        "Wi-Fi sampling finished, successful samples: $it"
                                    )
                                }
                            }
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

@Composable
fun InferenceScreen(
    targetOffset: Offset?,
    yaw: Float,
    waypoints: SnapshotStateList<Offset>,
    startFetching: () -> Unit,
    endFetching: () -> Unit,
    imuOffset: Offset?,
    wifiOffset: Offset?
) {
    var scaleFactor by remember { mutableFloatStateOf(5f) }

    // Shaking Time (ms)
    val shakingTime = 20
    var startTime = System.currentTimeMillis()
    var accumulatedOffset by remember { mutableStateOf(Offset.Zero) }
    var accumulatedScaleFactor by remember { mutableFloatStateOf(1f) }
    val configuration = LocalConfiguration.current

    // Retrieve screen width and height
    val screenWidthPx = with(LocalDensity.current) {configuration.screenWidthDp.dp.toPx()}
    val screenHeightPx = with(LocalDensity.current) {configuration.screenHeightDp.dp.toPx()}

    // We will map the map width to the screen width: pxRatio m/px
    val widthLength = 277f
    val meterPerPixel = widthLength / screenWidthPx

    // Variables to handle gestures
    val moveRedBirdToCenterOffset = IntOffset(-(screenWidthPx/100.0f).toInt(), -(screenHeightPx/9.6f).toInt()) * 5f
    var markerOffset by remember { mutableStateOf(Offset(0f, 0f)) }
    var mapDragOffset by remember { mutableStateOf(IntOffset(0, 0)) }

    // Variables to handle positional change
    var positionOffset by remember { mutableStateOf(Offset.Zero) }
    var previousPositionOffset by remember { mutableStateOf(Offset.Zero) }

    // Use for the Tip window to save the waypoint
    var showTipWindow by remember { mutableStateOf(false) }
    var tipPosition by remember { mutableStateOf(Offset(0f, 0f)) }
    var canvasSize by remember { mutableStateOf(Offset(0f, 0f)) } // Store canvas size
    var clicked by remember { mutableStateOf(false) }

    Column (modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier
            .fillMaxSize(0.8f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { offset ->
                        showTipWindow = true
                        tipPosition = offset // Capture the position
                    }
                )
            }
            .pointerInput(Unit) {
                // Detect transform gestures
                detectTransformGestures { _, pan, zoom, _ ->
                    showTipWindow = false
                    val previousScaleFactor = scaleFactor
                    scaleFactor = kotlin.math.min(5.0f, scaleFactor * zoom)
                    scaleFactor = kotlin.math.max(5.0f, scaleFactor * zoom)
                    accumulatedScaleFactor *= (scaleFactor / previousScaleFactor)
                    // Handle Pan
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - startTime > shakingTime) {
                        // Handle More Logics Here
                        mapDragOffset = IntOffset(
                            (mapDragOffset.x + accumulatedOffset.x).roundToInt(),
                            (mapDragOffset.y + accumulatedOffset.y).roundToInt()
                        )
                        markerOffset =
                            (markerOffset - moveRedBirdToCenterOffset.toOffset() - mapDragOffset.toOffset()) * accumulatedScaleFactor + moveRedBirdToCenterOffset.toOffset() + mapDragOffset.toOffset() + accumulatedOffset
                        startTime = currentTime
                        accumulatedOffset = Offset.Zero
                        accumulatedScaleFactor = 1f
                    }
                    accumulatedOffset =
                        Offset(accumulatedOffset.x + pan.x, accumulatedOffset.y + pan.y)
                }
            }
        ) {

            // Background image of the map
            Image(
                painter = painterResource(id = R.drawable.academic_building_g),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { moveRedBirdToCenterOffset + mapDragOffset }
                    .scale(scaleFactor)
            )

            // Notice that here we use the red bird as the zero point
            Canvas(modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val size = coordinates.size // Get the size of the canvas
                    canvasSize = Offset(size.width.toFloat(), size.height.toFloat())
                }
            ) {
                // Convert Position into Pixel Offset
                val positionDelta = positionOffset - previousPositionOffset
                val deltaPixels = positionDelta / meterPerPixel * scaleFactor
                previousPositionOffset = positionOffset
                markerOffset += deltaPixels
                // (0, 0) -> RedBird; Unit: Screen Pixel
                drawUserMarker(this, markerOffset.x, markerOffset.y, yaw)
                // waypoints
                drawWaypoints(
                    this,
                    meterPerPixel,
                    scaleFactor,
                    screenWidthPx,
                    screenHeightPx,
                    markerOffset,
                    positionOffset,
                    waypoints
                )
            }

            if (showTipWindow) {
                Canvas(modifier = Modifier
                    .offset { IntOffset(tipPosition.x.toInt(), tipPosition.y.toInt()) }) {
                    this.drawCircle(
                        color = Color(0xFFFF0000), // Glow color: #77B6EA
                        radius = 25f,
                    )
                }
                FilledCardExample(tipPosition,
                    onConfirm = {
                        val pointToMarker =
                            tipPosition - markerOffset - Offset(canvasSize.x / 2, canvasSize.y / 2)
                        val realOffset = pointToMarker / scaleFactor * meterPerPixel
                        waypoints.add(realOffset + positionOffset)
                        showTipWindow = false
                        Log.d("Map", "New waypoint ${realOffset.x}, ${realOffset.y}")
                    },
                    onDismiss = { showTipWindow = false }
                )
            }
        }
        Text(text = when {
                !clicked -> "Ready to go"
                else -> "yaw ${yaw.roundToInt()}, ${imuOffset?.x?.roundToInt()}, ${imuOffset?.y?.roundToInt()}, ${wifiOffset?.x?.roundToInt()}, ${wifiOffset?.y?.roundToInt()}, ${targetOffset?.x?.roundToInt()}, ${targetOffset?.y?.roundToInt()}"
            }
        )
        Button(onClick = {
            if (!clicked) { startFetching()}
            else { endFetching() }
            clicked = !clicked
        }, colors = if (clicked) {
            ButtonDefaults.buttonColors(containerColor = Color.Red )
        } else {
            ButtonDefaults.buttonColors()
        }) {
            Text(text = if (clicked) "Stop Tracking" else "Start Tracking")
        }

        // A dummy position updater
        LaunchedEffect(targetOffset) {
            val fps = 60
            val periodTime = 1000
            while (true) {
                val totalFrames = periodTime / 1000 * fps
                val step = (targetOffset?.minus(positionOffset))?.div(totalFrames.toFloat())
                for (i in 0..totalFrames) {
                    if (step != null) {
                        positionOffset += step
                    }
                    delay((1000 / fps).toLong())
                }
            }
        }
    }


}

@Composable
fun TrackingScreen(
    context: SensorUtils.SensorDataListener,
    waypoints: SnapshotStateList<Offset>,
    sensorManager: SensorUtils,
    wifiManager: WifiManager,
    timer: TimerUtils
) {
    var scaleFactor by remember { mutableFloatStateOf(5f) }

    // Shaking Time (ms)
    val shakingTime = 20
    var startTime = System.currentTimeMillis()
    var accumulatedOffset by remember { mutableStateOf(Offset.Zero) }
    var accumulatedScaleFactor by remember { mutableFloatStateOf(1f) }
    val configuration = LocalConfiguration.current

    // Retrieve screen width and height
    val screenWidthPx = with(LocalDensity.current) {configuration.screenWidthDp.dp.toPx()}
    val screenHeightPx = with(LocalDensity.current) {configuration.screenHeightDp.dp.toPx()}

    // We will map the map width to the screen width: pxRatio m/px
    val widthLength = 277f
    val meterPerPixel = widthLength / screenWidthPx

    // Variables to handle gestures
    val moveRedBirdToCenterOffset = IntOffset(-(screenWidthPx/100.0f).toInt(), -(screenHeightPx/9.6f).toInt()) * 5f
    var markerOffset by remember { mutableStateOf(Offset(0f, 0f)) }
    var mapDragOffset by remember { mutableStateOf(IntOffset(0, 0)) }

    // Variables to handle positional change
    var positionOffset by remember { mutableStateOf(Offset.Zero) }
    var previousPositionOffset by remember { mutableStateOf(Offset.Zero) }

    // Use for the Tip window to save the waypoint
    var showTipWindow by remember { mutableStateOf(false) }
    var tipPosition by remember { mutableStateOf(Offset(0f, 0f)) }
    var canvasSize by remember { mutableStateOf(Offset(0f, 0f)) } // Store canvas size

    var clicked by remember { mutableStateOf(false) }
    var savingDir by remember { mutableStateOf("0") }
//    val scope = CoroutineScope(Dispatchers.IO)
//    val timer = TimerUtils(scope)
    var waiting4label by remember { mutableStateOf(true) }
    Column (modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center){
        Box(modifier = Modifier
            .fillMaxHeight(0.8f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { offset ->
                        showTipWindow = true
                        tipPosition = offset // Capture the position
                    }
                )
            }
            .pointerInput(Unit) {
                // Detect transform gestures
                detectTransformGestures { _, pan, zoom, _ ->
                    showTipWindow = false
                    val previousScaleFactor = scaleFactor
                    scaleFactor = kotlin.math.min(5.0f, scaleFactor * zoom)
                    scaleFactor = kotlin.math.max(5.0f, scaleFactor * zoom)
                    accumulatedScaleFactor *= (scaleFactor / previousScaleFactor)
                    // Handle Pan
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - startTime > shakingTime) {
                        // Handle More Logics Here
                        mapDragOffset = IntOffset(
                            (mapDragOffset.x + accumulatedOffset.x).roundToInt(),
                            (mapDragOffset.y + accumulatedOffset.y).roundToInt()
                        )
                        markerOffset =
                            (markerOffset - moveRedBirdToCenterOffset.toOffset() - mapDragOffset.toOffset()) * accumulatedScaleFactor + moveRedBirdToCenterOffset.toOffset() + mapDragOffset.toOffset() + accumulatedOffset
                        startTime = currentTime
                        accumulatedOffset = Offset.Zero
                        accumulatedScaleFactor = 1f
                    }
                    accumulatedOffset =
                        Offset(accumulatedOffset.x + pan.x, accumulatedOffset.y + pan.y)
                }
            }
        ) {
            // Background image of the map
            Image(
                painter = painterResource(id = R.drawable.academic_building_g),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { moveRedBirdToCenterOffset + mapDragOffset }
                    .scale(scaleFactor)
            )

            // Notice that here we use the red bird as the zero point
            Canvas(modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val size = coordinates.size // Get the size of the canvas
                    canvasSize = Offset(size.width.toFloat(), size.height.toFloat())
                }
            ) {
                // Convert Position into Pixel Offset
                val positionDelta = positionOffset - previousPositionOffset
                val deltaPixels = positionDelta / meterPerPixel * scaleFactor
                previousPositionOffset = positionOffset
                markerOffset += deltaPixels
                // (0, 0) -> RedBird; Unit: Screen Pixel
//                drawUserMarker(this, markerOffset.x, markerOffset.y, yaw)
                // waypoints
                drawWaypoints(
                    this,
                    meterPerPixel,
                    scaleFactor,
                    screenWidthPx,
                    screenHeightPx,
                    markerOffset,
                    positionOffset,
                    waypoints
                )
            }

            if (showTipWindow) {
                Canvas(modifier = Modifier
                    .offset { IntOffset(tipPosition.x.toInt(), tipPosition.y.toInt()) }) {
                    this.drawCircle(
                        color = Color(0xFFFF0000), // Glow color: #77B6EA
                        radius = 25f,
                    )
                }
                FilledCardExample(tipPosition,
                    onConfirm = {
                        val pointToMarker =
                            tipPosition - markerOffset - Offset(canvasSize.x / 2, canvasSize.y / 2)
                        val realOffset = pointToMarker / scaleFactor * meterPerPixel
                        waypoints.add(realOffset + positionOffset)
                        showTipWindow = false
                        waiting4label = false
                        Log.d("Map", "New waypoint ${realOffset.x}, ${realOffset.y}")
                    },
                    onDismiss = { showTipWindow = false }
                )
            }
        }
        Text(text = when {
                !clicked -> "Ready to go"
                waiting4label -> "Label No.${waypoints.size} waypoint!"
                else -> "Grab IMU data..."
            }, color = when {
                !clicked -> Color.Green
                waiting4label -> Color.Red
                else -> Color(0xFFFFA500)
            }
        )
        TextField(
            value = savingDir,
            onValueChange = { newText ->
                savingDir = newText
            },
            label = { Text("# of trajectory") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )
        // Start Tracking
        Button(onClick = {
            if (!clicked) {
                val currentTimeMillis = System.currentTimeMillis()
                val currentTime = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(currentTimeMillis)
                timer.runSensorTaskAtFrequency(
                    sensorManager,
                    0.05,
                    currentTime,
                    "Trajectory${savingDir}",
                    "trajectory"
                ) {
                    Log.d("Sensor Finished", "Sampling finished, successful samples: $it")
                }

                timer.runWifiTaskAtFrequency(
                    wifiManager,
                    5f.toDouble(),
                    currentTime,
                    "Trajectory${savingDir}",
                    true,
                    getWaitingFlag = {
                        waiting4label
                    },
                    setWaitingFlag = {
                        waiting4label = true
                    },
                    getWaypoint = {
                        waypoints.last()
                    },
                    needWait = true
                ) {
                    Log.d("Wi-Fi Finished", "Sampling finished, successful samples: $it")
                }

                sensorManager.startMonitoring(context)
                clicked = true
            } else {
                sensorManager.stopMonitoring()
                waypoints.clear()
                savingDir = (savingDir.toInt() + 1).toString()
                timer.stopTask()
//                scope.cancel()
                clicked = false
            }
        }, colors = if (clicked) {
            ButtonDefaults.buttonColors(containerColor = Color.Red )
        } else {
            ButtonDefaults.buttonColors()
        }) {
            Text(text = if (clicked) "Stop Sampling" else "Start Sampling")
        }
    }
}


fun drawUserMarker(drawScope: DrawScope,
                   x: Float, y: Float,
                   direction: Float,
                   alpha: Float=1.0f) {
    val centerX = x + drawScope.center.x
    val centerY = y + drawScope.center.y
    // Radius
    val innerRadius = 35f
    val outerRadius = 40f
    // Draw triangle for direction
    val originAngle = Math.toRadians(120f.toDouble())
    val directionAngle = Math.toRadians((direction+180).toDouble())

    // Calculate the points of the triangle based on direction
    val triangleHeight = innerRadius / cos(originAngle / 2).toFloat()
    val tipX = centerX + triangleHeight * cos(directionAngle).toFloat()
    val tipY = centerY + triangleHeight * sin(directionAngle).toFloat()

    val leftBaseX = centerX + innerRadius * cos(directionAngle + originAngle / 2).toFloat()
    val leftBaseY = centerY + innerRadius * sin(directionAngle + originAngle / 2).toFloat()

    val rightBaseX = centerX + innerRadius * cos(directionAngle - originAngle / 2).toFloat()
    val rightBaseY = centerY + innerRadius * sin(directionAngle - originAngle / 2).toFloat()

    // Create a path for the triangle
    val path = Path().apply {
        moveTo(tipX, tipY) // Move to tip of the triangle
        lineTo(leftBaseX, leftBaseY) // Left bottom corner
        lineTo(rightBaseX, rightBaseY) // Right bottom corner
        close() // Close the path to form a triangle
    }

    // Draw the triangle
    drawScope.drawPath(
        path = path,
        color = Color(0xFF77B6EA).copy(alpha=alpha) // Fill color
    )

    // Draw the triangle outline
    drawScope.drawPath(
        path = path,
        color = Color(0xFFE8EEF2).copy(alpha=alpha), // Outline color
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = outerRadius - innerRadius)
    )

    // Outer glow
    drawScope.drawCircle(
        color = Color(0xFFE8EEF2).copy(alpha=alpha),
        radius = outerRadius, // Larger radius for greater glow
        center = Offset(centerX, centerY)
    )
    // Inner glow
    drawScope.drawCircle(
        color = Color(0xFF77B6EA).copy(alpha=alpha), // Glow color: #77B6EA
        radius = innerRadius, // Radius of glow
        center = Offset(centerX, centerY)
    )
}

fun drawWaypoints(drawScope: DrawScope, meterPerPixel: Float, scaleFactor: Float,
                  screenWidth: Float, screenHeight: Float,
                  markerOffset: Offset, positionOffset: Offset,
                  waypoints: SnapshotStateList<Offset>){
    var index = 0
    for(waypoint in waypoints){
        index += 1
        val realDelta = waypoint - positionOffset
        val pixelDelta = realDelta / meterPerPixel * scaleFactor
        Log.d("Map", "Pixel Delta: ${pixelDelta.x}, ${pixelDelta.y}")
        var drawPosition = pixelDelta + markerOffset + drawScope.center
        drawScope.drawCircle(
            color = Color(0xFFFF0000),
            radius = 20f, // Radius of glow
            center = drawPosition
        )
        drawScope.drawContext.canvas.nativeCanvas.apply {
            drawText(
                index.toString(), // Convert the index to string
                drawPosition.x + 6 * scaleFactor,
                drawPosition.y + 6 * scaleFactor,
                Paint().apply {
                    color = android.graphics.Color.parseColor("#FF0000") // Set the desired text color
                    textSize = 50f // Set the desired text size
                }
            )
        }
    }
}

@Composable
fun FilledCardExample(offset: Offset, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardColors(Color.White, Color(0xFF77B6EA), Color(0xFF77B6EA), Color(0xFF77B6EA)),
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .padding(5.dp)
    ) {
        Text(text = "${offset.x}, ${offset.y}")
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

class SensorUtils(context: Context): SensorEventListener {
    private var sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var rotationVectorSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var stepCountSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var accSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var singleStepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var lastRotationVector: FloatArray? = null
    private var lastStepCount: Float? = null
    private var lastAcc: FloatArray? = null

    interface SensorDataListener {
        fun onRotationVectorChanged(rotationVector: FloatArray)
        fun onStepCountChanged(stepCount: Float)
        fun onAccChanged(acc: FloatArray)
        fun onSingleStepChanged()
    }
    private var sensorDataListener: SensorDataListener? = null

    fun startMonitoring(listener: SensorDataListener) {
        this.sensorDataListener = listener
        val rotationSuccess = sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_FASTEST)
        val stepSuccess = sensorManager.registerListener(this, stepCountSensor, SensorManager.SENSOR_DELAY_FASTEST)
        val accSuccess = sensorManager.registerListener(this, accSensor, SensorManager.SENSOR_DELAY_FASTEST)
        val singleStepSuccess = sensorManager.registerListener(this, singleStepSensor, SensorManager.SENSOR_DELAY_FASTEST)

        if (!rotationSuccess) {
            Log.e("SensorRegister", "Failed to register rotation vector sensor listener")
        }
        if (!stepSuccess) {
            Log.e("SensorRegister", "Failed to register step count sensor listener")
        }
        if (!accSuccess) {
            Log.e("SensorRegister", "Failed to register accelerator sensor listener")
        }
        if (!singleStepSuccess) {
            Log.e("SensorRegister", "Failed to register step detector sensor listener")
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
            Sensor.TYPE_ACCELEROMETER -> {
                lastAcc = event.values
                sensorDataListener?.onAccChanged(event.values)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                if (event.values[0] == 1.0f) {
                    sensorDataListener?.onSingleStepChanged()
                }
//                Log.d("Step detector", event.values.toString())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("Sensor Accuracy Changed", "Accuracy changed to: $accuracy")
    }

    fun getLastRotationVector(): FloatArray? = lastRotationVector
    fun getLastStepCount(): Float? = lastStepCount
    fun getLastAcc(): FloatArray? = lastAcc
}

@Suppress("DEPRECATION")
class TimerUtils (private val coroutineScope: CoroutineScope, context: Context){
    private var isSensorTaskRunning = AtomicBoolean(false)
    private var isWifiTaskRunning = AtomicBoolean(false)
    private var blockListenUserLabel = AtomicBoolean(true)
    private var sensorJob: Job? = null
    private var wifiJob: Job? = null
    private val context = context

    private fun collectSensorData(
        sensorManager: SensorUtils,
        rotationFile: File,
        eulerFile: File,
        stepFile: File,
    ) {
        if (!isSensorTaskRunning.get()) return

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
            val eulerWriter = FileWriter(eulerFile, true)
            // Calculate and write yaw data
            val rotationMatrix = FloatArray(16)
            Log.d("log", rotationVector.joinToString(" "))
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
            val orientations = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientations)
            val yaw = Math.toDegrees(orientations[0].toDouble()).toFloat()
            val roll = Math.toDegrees(orientations[1].toDouble()).toFloat()
            val pitch = Math.toDegrees(orientations[2].toDouble()).toFloat()
            eulerWriter.append("$currentTime $yaw $roll $pitch\n")
            Log.d("Save", "$currentTime $yaw $roll $pitch")
            eulerWriter.flush()
            eulerWriter.close()
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
    }

    private fun collectWiFiData(
        wifiManager: WifiManager,
        wifiFile: File,
        collectWaypoint: Boolean,
        waypointPosition: Offset? = null
    ) {
        if (!isWifiTaskRunning.get()) return
        val (wifiResults, success) = wifiScan(wifiManager)
        if (success) {
            try {
                val wifiWriter = FileWriter(wifiFile, true)
                if (collectWaypoint) {
                    wifiWriter.append("${waypointPosition?.x}, ${waypointPosition?.y}\n")
                }
                wifiWriter.append("$wifiResults\n")
                wifiWriter.flush()
                wifiWriter.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    fun runSensorTaskAtFrequency(
        sensorManager: SensorUtils,
        frequency: Double,
        timestamp: String,
        dirName: String,
        selectedRunnable: String,
        onComplete: (Int) -> Unit
    ) {
//        val mainDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "WiMU data")
        val mainDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WiMU data")
        Log.d("DIR", mainDir.toString())
        if (!mainDir.exists()) {
            mainDir.mkdirs()
        }
        var dir = File(mainDir, timestamp)
        if (dirName != "") {
            dir = File(mainDir, dirName)
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val stepFile = File(dir, "step.txt")
        val rotationFile = File(dir, "rotation.txt")
        val eulerFile = File(dir, "euler.txt")

        isSensorTaskRunning.set(true)
        sensorJob = coroutineScope.launch {
            while (isSensorTaskRunning.get() && isActive) {
                collectSensorData(sensorManager, rotationFile, eulerFile, stepFile)

                when (selectedRunnable) {
                    "point" -> {
                        delay((frequency * 1000).toLong())
                    }

                    "trajectory" -> {
                        try {
                            while (blockListenUserLabel.get() && isActive) {
                                Log.d("IMU", "IMU waiting, $isActive")
                                delay(1000)
                            }
                            delay((frequency * 1000).toLong())
                        } catch (e: CancellationException) {
                            break
                        }
                    }
                }
            }
        }
    }

    fun runWifiTaskAtFrequency(
        wifiManager: WifiManager,
        frequencyY: Double, // Wi-Fi 采集频率 (秒)
        timestamp: String,
        dirName: String,
        collectWaypoint: Boolean,
        getWaitingFlag: () -> Boolean = {true},
        setWaitingFlag: () -> Unit = {},
        getWaypoint: () -> Offset = {Offset.Zero},
        needWait: Boolean = false,
        onComplete: (Int) -> Unit,
    ) {
//        val mainDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "WiMU data")
        val mainDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WiMU data")
        Log.d("Maindir", mainDir.toString())
        if (!mainDir.exists()) {
            mainDir.mkdirs()
        }
        var dir = File(mainDir, timestamp)
        if (dirName != "") {
            dir = File(mainDir, dirName)
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val wifiFile = File(dir, "wifi.txt")
        isWifiTaskRunning.set(true)

        wifiJob = coroutineScope.launch {
            while (isWifiTaskRunning.get() && isActive) {
                try {
                    var waypointPosition: Offset? = null
                    if (needWait) {
                        setWaitingFlag()
                        while (getWaitingFlag() && isActive) {
                            Log.d("WIFI", "Wifi waiting, $isActive")
                            delay(1000)
                        }
                        blockListenUserLabel.set(true)
                        waypointPosition = getWaypoint()
                        blockListenUserLabel.set(false)
                    }
                    else {
                        waypointPosition = getWaypoint()
                    }
                    collectWiFiData(
                        wifiManager,
                        wifiFile,
                        collectWaypoint,
                        waypointPosition
                    )
                    delay((frequencyY * 1000).toLong())
                }catch (e: CancellationException){
                    break
                }
            }
        }
    }

    // 提供停止任务的方法
    fun stopTask() {
        sensorJob?.cancel(cause = CancellationException("Sensor task finished"))
        wifiJob?.cancel(cause = CancellationException("wifi task finished"))
        isSensorTaskRunning.set(false)  // 将标志设为false，停止任务
        isWifiTaskRunning.set(false)
        blockListenUserLabel.set(true)
    }
}

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun wifiScan(wifiManager: WifiManager): Pair<String, Boolean> {
    val success = wifiManager.startScan()
    if (success) {
        val scanResults = wifiManager.scanResults
        // TODO: currentTimeMillis change
        val currentTime = System.currentTimeMillis()
        val resultList = scanResults.map { scanResult ->
            "$currentTime ${scanResult.SSID} ${scanResult.BSSID} ${scanResult.frequency} ${scanResult.level}"
        }

        val resultString = resultList.joinToString("\n")  // 将每个结果拼接为字符串，使用换行分隔
        Log.d("OUT", resultString)
        return Pair(resultString, true)
    } else {
        Log.e("ERR", "Scanning failed!")
        return Pair("", false)
    }
}
