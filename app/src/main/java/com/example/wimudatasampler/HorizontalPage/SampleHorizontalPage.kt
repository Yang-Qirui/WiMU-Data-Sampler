package com.example.wimudatasampler.HorizontalPage

import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wimudatasampler.utils.SensorUtils
import com.example.wimudatasampler.utils.TimerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SampleHorizontalPage(
    context: SensorUtils.SensorDataListener,
    sensorManager: SensorUtils,
    wifiManager: WifiManager,
    timer: TimerUtils,
    setStartSamplingTime: (String) -> Unit,
    yaw: Float,
    pitch: Float,
    roll: Float,
    isMonitoringAngles: Boolean,
    toggleMonitoringAngles: () -> Unit,
    waypoints: SnapshotStateList<Offset>,
    changeBeta: (Float) -> Unit,
    getBeta: () -> Float
) {

    var wifiFreq by remember {
        mutableStateOf("3")
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
    Column {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Spacer(modifier = Modifier.height(16.dp))
            // Select a waypoint to collect data
            Button(onClick = {
                selectorExpanded = true
            }) {
                if (selectedValue == "") {
                    Text("Collecting Unlabeled Data")
                } else {
                    Text("Collect Waypoint $selectedValue")
                }
            }
            DropdownMenu(
                expanded = selectorExpanded,
                onDismissRequest = { selectorExpanded = false },
                Modifier.fillMaxWidth()
            ) {
                DropdownMenuItem(text = { Text("Unlabeled Data") }, onClick = {
                    selectedValue = ""
                    selectorExpanded = false
                })
                for ((index, waypoint) in waypoints.withIndex()) {
                    DropdownMenuItem(
                        text = { Text("Waypoint ${index + 1}: ${waypoint.x}, ${waypoint.y}") },
                        onClick = {
                            selectedValue = "${index + 1}"
                            selectorExpanded = false
                        })
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
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
            OutlinedTextField(
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
            OutlinedTextField(
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
                        val currentTime =
                            SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(
                                currentTimeMillis
                            )
                        val wifiFrequency = wifiFreq.toDouble()
                        val sensorFrequency = sensorFreq.toDouble()
                        setStartSamplingTime(currentTime)
                        if (selectedValue != "") {
                            dirName = "Waypoint-${selectedValue}-$currentTime"
                        }
                        scope.launch {
                            timer.runSensorTaskAtFrequency(
                                sensorManager,
                                sensorFrequency,
                                currentTime,
                                dirName,
                                "point"
                            )
                        }
                        if (selectedValue != "") {
                            val waypointPosition = waypoints[selectedValue.toInt() - 1]
                            scope.launch {
                                timer.runWifiTaskAtFrequency(
                                    wifiManager,
                                    wifiFrequency,
                                    currentTime,
                                    dirName,
                                    false,
                                    waypointPosition
                                )
                            }
                        }
                        else {
                            scope.launch {
                                timer.runWifiTaskAtFrequency(
                                    wifiManager,
                                    wifiFrequency,
                                    currentTime,
                                    dirName,
                                    true
                                )
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
                    ButtonDefaults.buttonColors(containerColor = Color.Red)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                // 根据当前状态设置按钮文本
                Text(text = if (isSampling) "Stop Sampling" else "Start Sampling")
            }
        }
    }

}