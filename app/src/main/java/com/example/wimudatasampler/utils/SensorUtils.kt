package com.example.wimudatasampler.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class SensorUtils(context: Context) : SensorEventListener {
    private var sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var stepCountSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var accSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var singleStepSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

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