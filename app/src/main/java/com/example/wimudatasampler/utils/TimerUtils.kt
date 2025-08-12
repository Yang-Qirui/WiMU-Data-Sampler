package com.example.wimudatasampler.utils

import android.content.Context
import android.hardware.SensorManager
import android.os.Environment
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
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
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import java.util.UUID

class TimerUtils(
    private val deviceId: String,
    private val coroutineScope: CoroutineScope,
    private val getWiFiScanningResultCallback: () -> List<String>,
    private val clearWiFiScanningResultCallback: () -> Unit,
    private val wifiManagerScanning: () -> Boolean,
    context: Context,
){
    private var isSensorTaskRunning = AtomicBoolean(false)
    private var isWifiTaskRunning = AtomicBoolean(false)
    private var sensorJob: Job? = null
    private var wifiJob: Job? = null
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

        isSensorTaskRunning.set(true)
        sensorJob = coroutineScope.launch {
            while (isSensorTaskRunning.get() && isActive) {
                collectSensorData(sensorManager, eulerFile, singleStepFile)
                delay((frequency * 1000).toLong())
            }
        }
    }

    fun runWifiTaskAtFrequency(
        frequencyY: Double, // Wi-Fi 采集频率 (秒)
        timestamp: String,
        dirName: String,
        collectWaypoint: Boolean,
        waypointPosition: Offset? = null,
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
        isWifiTaskRunning.set(true)
        clearWiFiScanningResultCallback()
        wifiJob = coroutineScope.launch {
            while (isWifiTaskRunning.get() && isActive) {
                val invokeSuccess = wifiManagerScanning()
                Log.d("INVOKE_WRITE", "[${System.currentTimeMillis()}] $invokeSuccess")
                try {
                    if (!isWifiTaskRunning.get()) return@launch
                    val wifiResults = getWiFiScanningResultCallback().toMutableList()
                    wifiScanningInfo = "${System.currentTimeMillis()} $invokeSuccess"
                    try {
                        val wifiWriter = FileWriter(wifiFile, true)
                        if (collectWaypoint) {
                            wifiWriter.append("${waypointPosition?.x},${waypointPosition?.y}\n")
                        }
                        for (result in wifiResults) {
                            wifiWriter.append(result)
                        }
                        wifiWriter.flush()
                        wifiWriter.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    delay((frequencyY * 1000).toLong())
                }catch (e: CancellationException){
                    break
                }
            }
        }
    }

    fun setSavingDir(dir: String) {
        savingMainDir = dir
    }

    private suspend fun uploadSampledData(uploadUrl: String): Boolean {
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
            val response = uploadClient.post(uploadUrl) {
                parameter("device_id", deviceId)
                parameter("warehouse_id", "hkust") // TODO: 如果需要，可以作为参数传入
                setBody(MultiPartFormDataContent(
                    formData {
                        // 1. 添加元数据 (meta-data)
                        append("collection_time", System.currentTimeMillis() / 1000) // 只上传目录名，而不是完整路径
                        append("is_labeled", if (isCollectLabel) true else false)

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
    fun stopTask(apiBaseUrl:String) {
        sensorJob?.cancel(cause = CancellationException("Sensor task finished"))
        wifiJob?.cancel(cause = CancellationException("wifi task finished"))
        isSensorTaskRunning.set(false)
        isWifiTaskRunning.set(false)
        uploadJob = uploadServiceScope.launch {
            uploadSampledData(
                uploadUrl = "$apiBaseUrl/data/upload"
            )
        }
    }
}