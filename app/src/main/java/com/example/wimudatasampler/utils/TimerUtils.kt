package com.example.wimudatasampler.utils

import android.content.Context
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Environment
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.example.wimudatasampler.wifiScan
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
            //Log.d("log", rotationVector.joinToString(" "))
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
            val orientations = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientations)
            val yaw = Math.toDegrees(orientations[0].toDouble()).toFloat()
            val roll = Math.toDegrees(orientations[1].toDouble()).toFloat()
            val pitch = Math.toDegrees(orientations[2].toDouble()).toFloat()
            eulerWriter.append("$currentTime $yaw $roll $pitch\n")
            //Log.d("Save", "$currentTime $yaw $roll $pitch")
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
        waypointPosition: Offset? = null,
    ) {
        val mainDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "WiMU data")
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