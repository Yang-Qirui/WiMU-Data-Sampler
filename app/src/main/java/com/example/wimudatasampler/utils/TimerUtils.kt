package com.example.wimudatasampler.utils

import android.annotation.SuppressLint
import android.bluetooth.le.ScanResult
import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TimerUtils(
    private val deviceId: String,
    private val coroutineScope: CoroutineScope,
    // Wi-Fi Callbacks
    private val getWiFiScanningResultCallback: () -> List<String>,
    private val clearWiFiScanningResultCallback: () -> Unit,
    private val wifiManagerScanning: () -> Boolean,
    // Bluetooth Callbacks
    private val getBluetoothScanningResultCallback: () -> List<ScanResult>, // 获取原始蓝牙数据
    private val clearBluetoothScanningResultCallback: () -> Unit, // 清理蓝牙数据缓存
    context: Context,
){
    private var isSensorTaskRunning = AtomicBoolean(false)
    private var isScanningTaskRunning = AtomicBoolean(false)
    private var sensorJob: Job? = null
    private var scanningJob: Job? = null
    private var uploadJob: Job? = null
    private val context = context
    var wifiScanningInfo by mutableStateOf("null")
    private var lastSingleStepTime: Long = 0
    private var savingMainDir: String = "unlabeled"
    private var dirPath: File? = null
    private val uploadClient = HttpClient(Android)
    private var isCollectLabel = false
    private val uploadServiceJob = Job()
    private val uploadServiceScope = CoroutineScope(Dispatchers.IO + uploadServiceJob)

    private fun collectSensorData(
        sensorManager: SensorUtils,
        eulerFile: File,
        singleStepFile: File,
    ) {
        if (!isSensorTaskRunning.get()) return
        // Use the last known sensor data, or fallback to default if not available
        val rotationVector = sensorManager.getLastRotationVector() ?: floatArrayOf(0f, 0f, 0f, 0f)
        val currentSingleStepTime = sensorManager.getLastSingleStepTime() ?: 0

        val currentTime = System.currentTimeMillis()

        try {
            val eulerWriter = FileWriter(eulerFile, true)
            val rotationMatrix = FloatArray(16)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
            val orientations = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientations)
            val yaw = Math.toDegrees(orientations[0].toDouble()).toFloat()
            val roll = Math.toDegrees(orientations[1].toDouble()).toFloat()
            val pitch = Math.toDegrees(orientations[2].toDouble()).toFloat()
            eulerWriter.append("$currentTime,$yaw,$roll,$pitch\n")
            eulerWriter.flush()
            eulerWriter.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        try {
            val singleStepWriter = FileWriter(singleStepFile, true)
            if (currentSingleStepTime != lastSingleStepTime) {
                singleStepWriter.append("$currentSingleStepTime\n")
            }
            singleStepWriter.flush()
            singleStepWriter.close()
            lastSingleStepTime = currentSingleStepTime
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun runSensorTaskAtFrequency(
        sensorManager: SensorUtils,
        frequency: Double,
        timestamp: String,
        dirName: String,
    ) {
        val appDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WiMU data")
        val mainDir = File(appDir, savingMainDir)
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
        dirPath = dir
        val eulerFile = File(dir, "euler.csv")
        val singleStepFile = File(dir, "step.csv")

        try {
            if (!eulerFile.exists()) {
                eulerFile.writeText("timestamp,yaw,roll,pitch\n")
            }
            if (!singleStepFile.exists()) {
                singleStepFile.writeText("timestamp\n")
            }
        } catch (e: IOException) {
            e.printStackTrace()
            // Handle error, maybe log it or stop the task
        }

        isSensorTaskRunning.set(true)
        sensorJob = coroutineScope.launch {
            while (isSensorTaskRunning.get() && isActive) {
                collectSensorData(sensorManager, eulerFile, singleStepFile)
                delay((frequency * 1000).toLong())
            }
        }
    }

    // 在你的 TimerUtils.kt 文件中

// --- 假设你的 TimerUtils 构造函数现在接收这些回调 ---
// private val getBluetoothScanningResultCallback: () -> List<ScanResult>, // 注意：类型从 List<String> 变为 List<ScanResult>
// private val clearBluetoothScanningResultCallback: () -> Unit,

    @SuppressLint("MissingPermission")
    fun runScanningTaskAtFrequency(
        frequencyY: Double, // Wi-Fi & 蓝牙 采集频率 (秒)
        timestamp: String,
        dirName: String,
        collectWaypoint: Boolean,
        waypointPosition: Offset? = null,
        isBluetoothTimeWindowEnabled: Boolean,
        bluetoothTimeWindowSeconds: Float
    ) {
        isCollectLabel = collectWaypoint
        val appDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WiMU data")
        val mainDir = File(appDir, savingMainDir)
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
        dirPath = dir
        val wifiFile = File(dir, "wifi.csv")
        val bluetoothFile = File(dir, "bluetooth.csv")
        val labelFile = if (collectWaypoint) File(dir, "label.csv") else null

        try {
            if (!wifiFile.exists()) {
                wifiFile.writeText("timestamp,ssid,bssid,frequency,level\n")
            }
            if (!bluetoothFile.exists()) {
                // MODIFIED: 使用了正确的蓝牙表头
                bluetoothFile.writeText("timestamp,device_name,mac_address,rssi,tx_power\n")
            }
            labelFile?.let {
                if (!it.exists()) {
                    it.writeText("waypoint_x,waypoint_y\n")
                    it.appendText("${waypointPosition?.x},${waypointPosition?.y}\n")
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        isScanningTaskRunning.set(true)
        clearWiFiScanningResultCallback()
        clearBluetoothScanningResultCallback() // 确保开始前清理蓝牙缓存

        scanningJob = coroutineScope.launch {
            var windowStartTime = System.currentTimeMillis()
            var windowEndTime = System.currentTimeMillis()
            while (isScanningTaskRunning.get() && isActive) {

                // --- Wi-Fi 部分逻辑保持不变 ---
                val invokeSuccess = wifiManagerScanning()
                Log.d("INVOKE_SCAN", "[${System.currentTimeMillis()}] Wi-Fi: $invokeSuccess, BT: triggered")
                try {
                    if (!isScanningTaskRunning.get()) return@launch
                    val wifiResults = getWiFiScanningResultCallback().toMutableList()
                    wifiScanningInfo = "${System.currentTimeMillis()} $invokeSuccess"
                    try {
                        val wifiWriter = FileWriter(wifiFile, true)
                        wifiResults.forEach { result ->
                            wifiWriter.append("$result\n")
                        }
                        wifiWriter.flush()
                        wifiWriter.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    // ******************************************************
                    // ************  蓝牙逻辑核心修改部分开始  ************
                    // ******************************************************

                    // 2. 获取原始的蓝牙扫描结果 (List<ScanResult>)
                    val allBluetoothResults = getBluetoothScanningResultCallback()

                    // 3. 应用时间窗口进行过滤
                    val filteredBluetoothResults = allBluetoothResults.filter { result ->
                        val resultTimestamp = System.currentTimeMillis() - SystemClock.elapsedRealtime() + (result.timestampNanos / 1_000_000)
                        resultTimestamp in windowStartTime..windowEndTime
                    }

                    Log.d("BluetoothFilter_Infer", "Filtering: ${allBluetoothResults.size} -> ${filteredBluetoothResults.size} results")
                    Log.d("BluetoothFilter_Infer", "Window: $windowStartTime - $windowEndTime")

                    // 添加详细日志
                    allBluetoothResults.forEach { result ->
                        val resultTimestamp = System.currentTimeMillis() - SystemClock.elapsedRealtime() + (result.timestampNanos / 1_000_000)
                        Log.d("BluetoothFilter_Infer",
                            "Device: ${result.device.address}, RSSI: ${result.rssi}, Time: $resultTimestamp, InWindow: ${resultTimestamp in windowStartTime..windowEndTime}")
                    }

                    // 4. 格式化过滤后的结果，并使用独立的蓝牙时间戳 (windowEndTime)
                    val bluetoothStringsToWrite = filteredBluetoothResults.map { result ->
                        "${windowEndTime},${result.device.name ?: "N/A"},${result.device.address},2462,${result.rssi},${result.txPower}\n"
                    }

                    // 5. 将格式化后的字符串写入文件
                    try {
                        val bluetoothWriter = FileWriter(bluetoothFile, true)
                        bluetoothStringsToWrite.forEach { resultString ->
                            bluetoothWriter.append("$resultString\n")
                        }
                        bluetoothWriter.flush()
                        bluetoothWriter.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }

                    // 6. 清理 FrontService 中的源数据列表，为下一个周期做准备
                    clearBluetoothScanningResultCallback()
                    // ************  蓝牙逻辑核心修改部分结束  ************

                    windowStartTime = System.currentTimeMillis()
                    windowEndTime = windowStartTime + (bluetoothTimeWindowSeconds*1000).toLong()
                    delay((frequencyY * 1000).toLong())


                } catch (e: CancellationException) {
                    break
                }
            }
        }
    }

    fun setSavingDir(dir: String) {
        savingMainDir = dir
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun uploadSampledData(warehouseName: String, uploadUrl: String): Boolean {
        val directory = dirPath
        if (directory == null || !directory.exists() || !directory.isDirectory) {
            Log.e("Upload", "Directory path is invalid or does not exist: $dirPath")
            return false
        }

        val filesToUpload = directory.listFiles()?.filter { it.isFile }
        if (filesToUpload.isNullOrEmpty()) {
            Log.w("Upload", "No non-empty files to upload in directory: ${directory.absolutePath}")
            return true // 目录为空或文件为空，视为“成功”，不进行上传。
        }
        Log.i("Upload", filesToUpload.toString())
        Log.i("Upload", "Starting upload process for directory: ${directory.name}")

        try {
//            val warehouseName = "wands"
            // 1. 获取一次当前时间的毫秒时间戳，并保存
            val currentTimeMillis = System.currentTimeMillis()

            // 2. 基于这个时间戳生成格式化的日期时间字符串
            //    - 定义格式化模板
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            //    - 将毫秒时间戳转换为带时区的ZonedDateTime，然后格式化
            val formattedDateTime = Instant.ofEpochMilli(currentTimeMillis)
                .atZone(ZoneId.systemDefault()) // 使用手机的默认时区
                .format(formatter)

            val response = uploadClient.post(uploadUrl) {
                parameter("device_id", deviceId)
                parameter("warehouse_id", warehouseName) // TODO: 如果需要，可以作为参数传入
                setBody(MultiPartFormDataContent(
                    formData {
                        // 1. 添加元数据 (meta-data)
                        append("data_name", value = directory.name)
                        append("collection_time", value = currentTimeMillis / 1000) // 只上传目录名，而不是完整路径
                        append("is_labeled", value = isCollectLabel)

                        // 2. 添加所有文件
                        filesToUpload.forEach { file ->
                            val key = file.name.removeSuffix(".csv")
                            append(key, file.readBytes(), Headers.build {
                                set(HttpHeaders.ContentType, "application/octet-stream")
                                set(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                            })
                            Log.d("Upload", "Added file to upload request: ${file.name}")
                        }
                    }
                ))
            }

            // 3. 处理服务器响应
            if (response.status == HttpStatusCode.OK) {
                Log.i("Upload", "Successfully uploaded all files and metadata for directory ${directory.name}!")
                return true
            } else {
                Log.e("Upload", "Failed to upload. Status: ${response.status}, Body: ${response.bodyAsText()}")
                return false
            }

        } catch (e: Exception) {
            Log.e("Upload", "An exception occurred during the upload process for directory ${directory.name}", e)
            return false
        }
    }


    // 提供停止任务的方法
    @RequiresApi(Build.VERSION_CODES.O)
    fun stopTask(warehouseName:String, apiBaseUrl:String) {
        sensorJob?.cancel(cause = CancellationException("Sensor task finished"))
        scanningJob?.cancel(cause = CancellationException("Scanning task finished"))
        isSensorTaskRunning.set(false)
        isScanningTaskRunning.set(false)
        uploadJob = uploadServiceScope.launch {
            uploadSampledData(
                warehouseName = warehouseName,
                uploadUrl = "$apiBaseUrl/data/upload"
            )
        }
    }
}