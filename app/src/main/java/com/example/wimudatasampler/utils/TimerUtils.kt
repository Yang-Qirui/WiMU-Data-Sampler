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
            eulerWriter.append("$currentTime $yaw $roll $pitch\n")
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
        val eulerFile = File(dir, "euler.txt")
        val singleStepFile = File(dir, "step.txt")

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
        val wifiFile = File(dir, "wifi.txt")
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
                            wifiWriter.append("${waypointPosition?.x}, ${waypointPosition?.y}\n")
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

    suspend fun uploadSampledData(uploadMetaUrl:String, uploadFileUrl: String): Boolean {
//        Log.d("Uploading")
        val directory = dirPath
        if (directory == null || !directory.exists() || !directory.isDirectory) {
            Log.e("UploadV2", "Directory path is invalid or does not exist: $dirPath")
            return false
        }
        val filesToUpload = directory.listFiles()?.filter { it.isFile }
        if (filesToUpload.isNullOrEmpty()) {
            Log.w("UploadV2", "No files to upload in directory: ${directory.absolutePath}")
            return true // 目录为空，也算作“成功”
        }
        val batchId = UUID.randomUUID().toString()
        val fileNames = filesToUpload.map { it.name }
        val manifest = UploadBatchManifest(
            batch_id = batchId,
            total_files = fileNames.size,
            device_id = getDeviceId(context.applicationContext),
            path_name = directory.path,
            data_type = if(isCollectLabel) "labeled" else "unlabeled"
        )
        try {
            val metaUploadResponse = uploadClient.post(uploadMetaUrl) {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(manifest))
            }
            if (metaUploadResponse.status != HttpStatusCode.OK) {
                Log.e("UploadV2", "Failed to initialize batch. Status: ${metaUploadResponse.status}. Body: ${metaUploadResponse.bodyAsText()}")
                return false
            }
            Log.i("UploadV2", "Batch initialized successfully on the server.")

            // 3. 第二步：并发上传所有文件到 /api/upload/chunk
            val uploadJobs = filesToUpload.map { file ->
                // 使用 async 启动并发任务，它会返回一个 Deferred<Boolean>
                uploadServiceScope.async {
                    uploadSingleFile(uploadFileUrl, batchId, file)
                }
            }

            // 等待所有上传任务完成，并获取它们的布尔结果
            val results = uploadJobs.awaitAll()

            // 检查是否所有上传都成功了
            val allUploadsSuccessful = results.all { it }
            if (allUploadsSuccessful) {
                Log.i("UploadV2", "All files for batch $batchId uploaded successfully!")
            } else {
                Log.e("UploadV2", "One or more files failed to upload for batch $batchId.")
            }
            return allUploadsSuccessful
        } catch (e: Exception) {
            Log.e("UploadV2", "An exception occurred during the upload process for batch $batchId", e)
            return false
        }
    }

    private suspend fun uploadSingleFile(baseUrl: String, batchId: String, file: File): Boolean {
        Log.d("UploadV2", "[${batchId}] Uploading chunk: ${file.name}...")
        try {
            val response = uploadClient.post(baseUrl) {
                setBody(MultiPartFormDataContent(
                    formData {
                        append("batch_id", batchId)
                        append("file", file.readBytes(), Headers.build {
                            set(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                            set(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                        })
                    }
                ))
            }
            if (response.status == HttpStatusCode.OK) {
                Log.d("UploadV2", "[${batchId}] Successfully uploaded chunk: ${file.name}")
                return true
            } else {
                Log.e("UploadV2", "[${batchId}] Failed to upload chunk ${file.name}. Status: ${response.status}, Body: ${response.bodyAsText()}")
                return false
            }
        } catch (e: Exception) {
            Log.e("UploadV2", "[${batchId}] Exception while uploading chunk ${file.name}", e)
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
                uploadMetaUrl = "$apiBaseUrl/upload_meta",
                uploadFileUrl = "$apiBaseUrl/upload"
            )
        }
    }
}