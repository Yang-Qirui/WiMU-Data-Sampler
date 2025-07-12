package com.example.wimudatasampler.HorizontalPage

import android.graphics.Paint
import android.net.wifi.WifiManager
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import com.example.wimudatasampler.R
import com.example.wimudatasampler.utils.SensorUtils
import com.example.wimudatasampler.utils.TimerUtils
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

//@Composable
//fun TrackingHorizontalPage(
//    context: SensorUtils.SensorDataListener,
//    waypoints: SnapshotStateList<Offset>,
//    sensorManager: SensorUtils,
//    wifiManager: WifiManager,
//    timer: TimerUtils
//) {
//    var scaleFactor by remember { mutableFloatStateOf(5f) }
//
//    // Shaking Time (ms)
//    val shakingTime = 20
//    var startTime = System.currentTimeMillis()
//    var accumulatedOffset by remember { mutableStateOf(Offset.Zero) }
//    var accumulatedScaleFactor by remember { mutableFloatStateOf(1f) }
//    val configuration = LocalConfiguration.current
//
//    // Retrieve screen width and height
//    val screenWidthPx = with(LocalDensity.current) {configuration.screenWidthDp.dp.toPx()}
//    val screenHeightPx = with(LocalDensity.current) {configuration.screenHeightDp.dp.toPx()}
//
//    // We will map the map width to the screen width: pxRatio m/px
//    val widthLength = 277f
//    val meterPerPixel = widthLength / screenWidthPx
//
//    // Variables to handle gestures
//    val moveRedBirdToCenterOffset = IntOffset(-(screenWidthPx/100.0f).toInt(), -(screenHeightPx/9.6f).toInt()) * 5f
//    var markerOffset by remember { mutableStateOf(Offset(0f, 0f)) }
//    var mapDragOffset by remember { mutableStateOf(IntOffset(0, 0)) }
//
//    // Variables to handle positional change
//    var positionOffset by remember { mutableStateOf(Offset.Zero) }
//    var previousPositionOffset by remember { mutableStateOf(Offset.Zero) }
//
//    // Use for the Tip window to save the waypoint
//    var showTipWindow by remember { mutableStateOf(false) }
//    var tipPosition by remember { mutableStateOf(Offset(0f, 0f)) }
//    var canvasSize by remember { mutableStateOf(Offset(0f, 0f)) } // Store canvas size
//
//    var clicked by remember { mutableStateOf(false) }
//    var savingDir by remember { mutableStateOf("0") }
////    val scope = CoroutineScope(Dispatchers.IO)
////    val timer = TimerUtils(scope)
//    var waiting4label by remember { mutableStateOf(true) }
//    Column(
//        modifier = Modifier.fillMaxSize(),
//        horizontalAlignment = Alignment.CenterHorizontally,
//        verticalArrangement = Arrangement.Center
//    ) {
//        Box(
//            modifier = Modifier
//                .fillMaxHeight(0.8f)
//                .pointerInput(Unit) {
//                    detectTapGestures(
//                        onLongPress = { offset ->
//                            showTipWindow = true
//                            tipPosition = offset // Capture the position
//                        }
//                    )
//                }
//                .pointerInput(Unit) {
//                    // Detect transform gestures
//                    detectTransformGestures { _, pan, zoom, _ ->
//                        showTipWindow = false
//                        val previousScaleFactor = scaleFactor
//                        scaleFactor = kotlin.math.min(5.0f, scaleFactor * zoom)
//                        scaleFactor = kotlin.math.max(5.0f, scaleFactor * zoom)
//                        accumulatedScaleFactor *= (scaleFactor / previousScaleFactor)
//                        // Handle Pan
//                        val currentTime = System.currentTimeMillis()
//                        if (currentTime - startTime > shakingTime) {
//                            // Handle More Logics Here
//                            mapDragOffset = IntOffset(
//                                (mapDragOffset.x + accumulatedOffset.x).roundToInt(),
//                                (mapDragOffset.y + accumulatedOffset.y).roundToInt()
//                            )
//                            markerOffset =
//                                (markerOffset - moveRedBirdToCenterOffset.toOffset() - mapDragOffset.toOffset()) * accumulatedScaleFactor + moveRedBirdToCenterOffset.toOffset() + mapDragOffset.toOffset() + accumulatedOffset
//                            startTime = currentTime
//                            accumulatedOffset = Offset.Zero
//                            accumulatedScaleFactor = 1f
//                        }
//                        accumulatedOffset =
//                            Offset(accumulatedOffset.x + pan.x, accumulatedOffset.y + pan.y)
//                    }
//                }
//        ) {
//            // Background image of the map
//            Image(
//                painter = painterResource(id = R.drawable.academic_building_g),
//                contentDescription = null,
//                modifier = Modifier
//                    .fillMaxSize()
//                    .offset { moveRedBirdToCenterOffset + mapDragOffset }
//                    .scale(scaleFactor)
//            )
//
//            // Notice that here we use the red bird as the zero point
//            Canvas(modifier = Modifier
//                .fillMaxSize()
//                .onGloballyPositioned { coordinates ->
//                    val size = coordinates.size // Get the size of the canvas
//                    canvasSize = Offset(size.width.toFloat(), size.height.toFloat())
//                }
//            ) {
//                // Convert Position into Pixel Offset
//                val positionDelta = positionOffset - previousPositionOffset
//                val deltaPixels = positionDelta / meterPerPixel * scaleFactor
//                previousPositionOffset = positionOffset
//                markerOffset += deltaPixels
//                // (0, 0) -> RedBird; Unit: Screen Pixel
////                drawUserMarker(this, markerOffset.x, markerOffset.y, yaw)
//                // waypoints
//                drawWaypoints(
//                    this,
//                    meterPerPixel,
//                    scaleFactor,
//                    screenWidthPx,
//                    screenHeightPx,
//                    markerOffset,
//                    positionOffset,
//                    waypoints
//                )
//            }
//
//            if (showTipWindow) {
//                Canvas(modifier = Modifier
//                    .offset { IntOffset(tipPosition.x.toInt(), tipPosition.y.toInt()) }) {
//                    this.drawCircle(
//                        color = Color(0xFFFF0000), // Glow color: #77B6EA
//                        radius = 25f,
//                    )
//                }
//                FilledCardExample(offset = tipPosition,
//                    showingOffset = tipPosition,
//                    onConfirm = {
//                        val pointToMarker =
//                            tipPosition - markerOffset - Offset(canvasSize.x / 2, canvasSize.y / 2)
//                        val realOffset = pointToMarker / scaleFactor * meterPerPixel
//                        waypoints.add(realOffset + positionOffset)
//                        showTipWindow = false
//                        waiting4label = false
//                        Log.d("Map", "New waypoint ${realOffset.x}, ${realOffset.y}")
//                    },
//                    onDismiss = { showTipWindow = false }
//                )
//            }
//        }
//        Text(text = when {
//            !clicked -> "Ready to go"
//            waiting4label -> "Label No.${waypoints.size} waypoint!"
//            else -> "Grab IMU data..."
//        }, color = when {
//            !clicked -> Color.Green
//            waiting4label -> Color.Red
//            else -> Color(0xFFFFA500)
//        }
//        )
//        TextField(
//            value = savingDir,
//            onValueChange = { newText ->
//                savingDir = newText
//            },
//            label = { Text("# of trajectory") },
//            keyboardOptions = KeyboardOptions(
//                keyboardType = KeyboardType.Decimal,
//                imeAction = ImeAction.Done
//            ),
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(horizontal = 32.dp)
//        )
//        // Start Tracking
//        Button(onClick = {
//            if (!clicked) {
//                val currentTimeMillis = System.currentTimeMillis()
//                val currentTime = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(currentTimeMillis)
//                timer.runSensorTaskAtFrequency(
//                    sensorManager,
//                    0.05,
//                    currentTime,
//                    "Trajectory${savingDir}",
//                    "trajectory"
//                ) {
//                    Log.d("Sensor Finished", "Sampling finished, successful samples: $it")
//                }
//
//                timer.runWifiTaskAtFrequency(
//                    wifiManager,
//                    5f.toDouble(),
//                    currentTime,
//                    "Trajectory${savingDir}",
//                    true,
//                    getWaitingFlag = {
//                        waiting4label
//                    },
//                    setWaitingFlag = {
//                        waiting4label = true
//                    },
//                    getWaypoint = {
//                        waypoints.last()
//                    },
//                    needWait = true
//                ) {
//                    Log.d("Wi-Fi Finished", "Sampling finished, successful samples: $it")
//                }
//
//                sensorManager.startMonitoring(context)
//                clicked = true
//            } else {
//                sensorManager.stopMonitoring()
//                waypoints.clear()
//                savingDir = (savingDir.toInt() + 1).toString()
//                timer.stopTask()
////                scope.cancel()
//                clicked = false
//            }
//        }, colors = if (clicked) {
//            ButtonDefaults.buttonColors(containerColor = Color.Red )
//        } else {
//            ButtonDefaults.buttonColors()
//        }) {
//            Text(text = if (clicked) "Stop Sampling" else "Start Sampling")
//        }
//    }
//}
//
//fun drawWaypoints(
//    drawScope: DrawScope, meterPerPixel: Float, scaleFactor: Float,
//    screenWidth: Float, screenHeight: Float,
//    markerOffset: Offset, positionOffset: Offset,
//    waypoints: SnapshotStateList<Offset>
//) {
//    var index = 0
//    for (waypoint in waypoints) {
//        index += 1
//        val realDelta = waypoint - positionOffset
//        val pixelDelta = realDelta / meterPerPixel * scaleFactor
//        Log.d("Map", "Pixel Delta: ${pixelDelta.x}, ${pixelDelta.y}")
//        var drawPosition = pixelDelta + markerOffset + drawScope.center
//        drawScope.drawCircle(
//            color = Color(0xFFFF0000),
//            radius = 20f, // Radius of glow
//            center = drawPosition
//        )
//        drawScope.drawContext.canvas.nativeCanvas.apply {
//            drawText(
//                index.toString(), // Convert the index to string
//                drawPosition.x + 6 * scaleFactor,
//                drawPosition.y + 6 * scaleFactor,
//                Paint().apply {
//                    color =
//                        android.graphics.Color.parseColor("#FF0000") // Set the desired text color
//                    textSize = 50f // Set the desired text size
//                }
//            )
//        }
//    }
//}