package com.example.wimudatasampler.utils

import android.content.Context
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
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

class TimerUtils(
    private val coroutineScope: CoroutineScope,
    private val getWiFiScanningResultCallback: () -> MutableList<String>,
    private val clearWiFiScanningResultCallback: () -> Unit,
    private val wifiManagerScanning: () -> Boolean,
    context: Context,
){
    private var isSensorTaskRunning = AtomicBoolean(false)
    private var isWifiTaskRunning = AtomicBoolean(false)
    private var isTestFreqTaskRunning = AtomicBoolean(false)
    private var sensorJob: Job? = null
    private var wifiJob: Job? = null
    private var testFreqJob: Job? = null
    private val context = context
    private var lastMinTimestamp by mutableLongStateOf(0)
    var lastTwoScanInterval by mutableLongStateOf(0)
    var wifiScanningInfo by mutableStateOf("null")
    private var lastSingleStepTime: Long = 0
    private var savingMainDir: String = "unlabeled"

    private fun collectSensorData(
        sensorManager: SensorUtils,
        rotationFile: File,
        eulerFile: File,
        stepFile: File,
        singleStepFile: File,
        singleStepRecordsFile: File
    ) {
        if (!isSensorTaskRunning.get()) return
        // Use the last known sensor data, or fallback to default if not available
        val rotationVector = sensorManager.getLastRotationVector() ?: floatArrayOf(0f, 0f, 0f, 0f)
        val stepCount = sensorManager.getLastStepCount() ?: 0f
        val currentSingleStepTime = sensorManager.getLastSingleStepTime() ?: 0
        val singleStepRecords = sensorManager.getStepTimestamps() ?: emptyList()

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

        // Write the step count data
        try {
            val stepWriter = FileWriter(stepFile, true)
            stepWriter.append("$currentTime $stepCount\n")
            stepWriter.flush()
            stepWriter.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        try {
            if (currentSingleStepTime != lastSingleStepTime) {
                val singleStepWriter = FileWriter(singleStepFile, true)
                singleStepWriter.append("$currentSingleStepTime\n")
                singleStepWriter.flush()
                singleStepWriter.close()
                lastSingleStepTime = currentSingleStepTime
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

        try {
            val singleStepRecordWriter = FileWriter(singleStepRecordsFile, false)
            for (item in singleStepRecords) {
                singleStepRecordWriter.write("$item\n")
            }
            singleStepRecordWriter.flush()
            singleStepRecordWriter.close()
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
        val stepFile = File(dir, "step.txt")
        val rotationFile = File(dir, "rotation.txt")
        val eulerFile = File(dir, "euler.txt")
        val singleStepFile = File(dir, "single_step.txt")
        val singleStepRecordsFile = File(dir, "single_step_rec.txt")


        isSensorTaskRunning.set(true)
        sensorJob = coroutineScope.launch {
            while (isSensorTaskRunning.get() && isActive) {
                collectSensorData(sensorManager, rotationFile, eulerFile, stepFile, singleStepFile, singleStepRecordsFile)
                delay((frequency * 1000).toLong())
            }
        }
    }

    fun runWifiTaskAtFrequency(
        wifiManager: WifiManager,
        frequencyY: Double, // Wi-Fi 采集频率 (秒)
        timestamp: String,
        dirName: String,
        collectWaypoint: Boolean,
        waypointPosition: Offset? = null,
    ) {
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
        val wifiFile = File(dir, "wifi.txt")
        isWifiTaskRunning.set(true)
        clearWiFiScanningResultCallback()
        wifiJob = coroutineScope.launch {
            while (isWifiTaskRunning.get() && isActive) {
                val invokeSuccess = wifiManagerScanning()
                Log.d("INVOKE", "[${System.currentTimeMillis()}] $invokeSuccess")
                try {
                    if (!isWifiTaskRunning.get()) return@launch
                    val wifiResults = getWiFiScanningResultCallback().toMutableList()
                    wifiScanningInfo = "${System.currentTimeMillis()} $invokeSuccess"
                    try {
                        val wifiWriter = FileWriter(wifiFile, false)
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

//    fun runTestFrequencyTask(
//        wifiManager: WifiManager,
//        frequency: Float
//    ) {
//        isTestFreqTaskRunning.set(true)
//        testFreqJob = coroutineScope.launch {
//            while (isTestFreqTaskRunning.get() && isActive) {
//                try {
//                    if (!isTestFreqTaskRunning.get()) return@launch
//                    val wifi = wifiScan(wifiManager)
//                    wifiScanningInfo = info
//                    if (success && latestMinTimestamp != lastMinTimestamp) {
//                        lastTwoScanInterval = latestMinTimestamp - lastMinTimestamp
//                        lastMinTimestamp = latestMinTimestamp
//                    }
//                    delay((frequency * 1000).toLong())
//                }catch (e: CancellationException){
//                    break
//                }
//            }
//        }
//    }

    fun setSavingDir(dir: String) {
        savingMainDir = dir
    }

    // 提供停止任务的方法
    fun stopTask() {
        sensorJob?.cancel(cause = CancellationException("Sensor task finished"))
        wifiJob?.cancel(cause = CancellationException("wifi task finished"))
        testFreqJob?.cancel(cause = CancellationException("wifi task finished"))
        isSensorTaskRunning.set(false)  // 将标志设为false，停止任务
        isWifiTaskRunning.set(false)
        isTestFreqTaskRunning.set(false)
    }
}