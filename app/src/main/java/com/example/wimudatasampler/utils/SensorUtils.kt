package com.example.wimudatasampler.utils

import MyStepDetector
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf

class SensorUtils(context: Context) : SensorEventListener {
    private var sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var stepCountSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var accSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var singleStepSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private var magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private var gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var myStepDetector = MyStepDetector()

    private var lastRotationVector: FloatArray? = null
    private var lastStepCount: Float? = null
    private var lastAcc: FloatArray? = null
    private var lastStepTimestamp: Long? = null
    private var stepTimestamps = mutableListOf<Long>()

    private var lastAccChanged = false
    private var lastMagChanged = false

    interface SensorDataListener {
        fun onRotationVectorChanged(rotationVector: FloatArray)
        fun onStepCountChanged(stepCount: Float)
        fun onAccChanged(acc: FloatArray)
        fun onSingleStepChanged()
        fun onMagChanged(mag: FloatArray)
        fun onMyStepChanged()
        fun updateOrientation()
    }

    private var sensorDataListener: SensorDataListener? = null

    fun startMonitoring(listener: SensorDataListener) {
        this.sensorDataListener = listener
        this.stepTimestamps = mutableListOf()
        try {
            val rotationSuccess = sensorManager.registerListener(
                this,
                rotationVectorSensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            val stepSuccess = sensorManager.registerListener(
                this,
                stepCountSensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            val accSuccess =
                sensorManager.registerListener(this, accSensor, SensorManager.SENSOR_DELAY_FASTEST)
            val singleStepSuccess = sensorManager.registerListener(
                this,
                singleStepSensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            val stepLengthSuccess = sensorManager.registerListener(
                this,
                accSensor,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            val magSuccess = sensorManager.registerListener(
                this,
                magnetometer,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            val gyroSuccess = sensorManager.registerListener(
                this,
                gyroscope,
                SensorManager.SENSOR_DELAY_FASTEST
            )
            // Register my step detector
            myStepDetector.registerListener(listener)
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
            if (!stepLengthSuccess) {
                Log.e("SensorRegister", "Failed to register accelerometer sensor listener")
            }
            if (!magSuccess) {
                Log.e("SensorRegister", "Failed to register magnetometer sensor listener")
            }
            if (!gyroSuccess) {
                Log.e("SensorRegister", "Failed to register gyroscope sensor listener")
            }
        }
        catch (e: Exception) {
            Log.d("Register Error", e.printStackTrace().toString())
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
                myStepDetector.onSensorChanged(event)
                lastAccChanged = true
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                Log.d("Triggered step detector", "Debug")
                if (event.values[0] == 1.0f) {
                    val ts = System.currentTimeMillis()
                    lastStepTimestamp = ts
                    stepTimestamps.add(ts)
                    sensorDataListener?.onSingleStepChanged()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                sensorDataListener?.onMagChanged(event.values)
                lastMagChanged = true
            }
        }
        if (lastAccChanged && lastMagChanged) {
            sensorDataListener?.updateOrientation()
            lastAccChanged = false
            lastMagChanged = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("Sensor Accuracy Changed", "Accuracy changed to: $accuracy")
    }

    fun getLastRotationVector(): FloatArray? = lastRotationVector
    fun getLastStepCount(): Float? = lastStepCount
    fun getLastSingleStepTime(): Long? = lastStepTimestamp
    fun getStepTimestamps(): List<Long> = stepTimestamps.toList()
}