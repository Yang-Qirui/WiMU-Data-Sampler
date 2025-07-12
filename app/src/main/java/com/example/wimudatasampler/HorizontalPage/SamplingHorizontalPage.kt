package com.example.wimudatasampler.HorizontalPage

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wimudatasampler.DataClass.MapModels
import com.example.wimudatasampler.MapViewModel
import com.example.wimudatasampler.R
import com.example.wimudatasampler.utils.ImageUtil.Companion.getImageFolderPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

@SuppressLint("DefaultLocale")
@Composable
fun SamplingHorizontalPage(
    context: Context,
    //ViewModel
    mapViewModel: MapViewModel,
    //UIState
    jDMode: Boolean,
    isCollectTraining: Boolean,
    isSampling: Boolean,
    //sampling Data
    yaw: Float?,
    pitch: Float?,
    roll: Float?,
    numOfLabelSampling: Int?, // Start from 0
    wifiScanningInfo: String?,
    wifiSamplingCycles: Float,
    sensorSamplingCycles: Float,
    saveDirectory: String,
    waypoints: SnapshotStateList<Offset>,
    //Function Action
    updateWifiSamplingCycles: (Float) -> Unit,
    updateSensorSamplingCycles: (Float) -> Unit,
    updateSaveDirectory: (String) -> Unit,
    updateIsCollectTraining: (Boolean) -> Unit,
    onStartSamplingButtonClicked: (indexOfLabelToSample: Int?, startScanningTime: Long) -> Unit,
    onStopSamplingButtonClicked: () -> Unit
) {
    var selectorExpanded by remember { mutableStateOf(false) }
    var showMarkLabelsWindow by remember { mutableStateOf(false) }
    var selectedValue by remember(numOfLabelSampling) {
        mutableStateOf(
            numOfLabelSampling?.let { (it + 1).toString() } ?: ""
        )
    }
    val selectedMap by mapViewModel.selectedMap.collectAsState()
    Column {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Display acceleration values
//            Text("X: ${accX.toInt()} Y: ${accY.toInt()} Z: ${accZ.toInt()} Step: $stepFromMyDetector")
            Text("yaw: ${yaw?.toInt()} pitch: ${pitch?.toInt()} roll: ${roll?.toInt()}")
//            Text("orientation: ${orientation.toInt()}")
//            Text("mag: $mag")
            Spacer(modifier = Modifier.height(16.dp))
            // Select a waypoint to collect data
            Button(
                enabled = !isSampling,
                onClick = {
                    selectorExpanded = true
                }
            ) {
                if (selectedValue == "") {
                    Text("Collecting Labeled Data")
                } else {
                    Text("Collect Waypoint $selectedValue")
                }
            }
            DropdownMenu(
                expanded = selectorExpanded,
                onDismissRequest = { selectorExpanded = false },
                Modifier.fillMaxWidth()
            ) {
                DropdownMenuItem(
                    text = { Text("Unlabeled Data") },
                    onClick = {
                        selectedValue = ""
                        selectorExpanded = false
                    }
                )
                for ((index, waypoint) in waypoints.withIndex()) {
                    DropdownMenuItem(
                        text = { Text("Waypoint ${index + 1}: ${waypoint.x}, ${waypoint.y}") },
                        onClick = {
                            selectedValue = "${index + 1}"
                            selectorExpanded = false
                        }
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(text = "ADD NEW POINT", color = MaterialTheme.colorScheme.tertiary)
                    }, onClick = {
                        showMarkLabelsWindow = true
                    }
                )
            }

            selectedMap?.let { map ->
                val folderPath = getImageFolderPath(context)
                val fullPath = remember(map) { File(folderPath, map.content).absolutePath }

                var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                var isLoading by remember { mutableStateOf(false) }

                LaunchedEffect(fullPath) {
                    if (isLoading) return@LaunchedEffect
                    isLoading = true
                    val bitmap = withContext(Dispatchers.IO) {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 1
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                        BitmapFactory.decodeFile(fullPath, options)
                    }
                    imageBitmap = bitmap?.asImageBitmap()
                    isLoading = false
                }

                if (!isLoading) {
                    imageBitmap?.let { bitmap ->
                        if (showMarkLabelsWindow) {
                            AlertDialog(
                                modifier = Modifier.fillMaxSize(),
                                onDismissRequest = { showMarkLabelsWindow = false },
                                title = {},
                                text = {
                                    MarkLabelsWindow(
                                        jDMode = jDMode,
                                        waypoints = waypoints,
                                        imageBitmap = bitmap,
                                        selectedMap = map,
                                        onAddMarkersFinishedButtonClicked = {
                                            showMarkLabelsWindow = false
                                        }
                                    )
                                },
                                confirmButton = {
                                    //NOTHING
                                }
                            )
                        }
                    } ?: ImageBitmap.imageResource(R.drawable.image_placeholder)
                } else {
                    CircularProgressIndicator()
                }
            }

            var curWifiSamplingCycles by remember { mutableStateOf(wifiSamplingCycles.toString()) }
            var curSensorSamplingCycles by remember { mutableStateOf(sensorSamplingCycles.toString()) }
            var curIsCollectTraining by remember { mutableStateOf(isCollectTraining) }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                enabled = !isSampling,
                value = curWifiSamplingCycles,
                onValueChange = { newText ->
                    curWifiSamplingCycles = newText
                    updateWifiSamplingCycles(newText.toFloat())
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
                enabled = !isSampling,
                value = curSensorSamplingCycles,
                onValueChange = { newText ->
                    curSensorSamplingCycles = newText
                    updateSensorSamplingCycles(newText.toFloat())
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
                enabled = !isSampling,
                value = saveDirectory,
                onValueChange = { newText ->
                    updateSaveDirectory(newText)
                },
                label = { Text("Save directory") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp)
            ) {
                Switch(
                    enabled = !isSampling,
                    checked = curIsCollectTraining,
                    onCheckedChange = {
                        curIsCollectTraining = it
                        updateIsCollectTraining(it)
                    },
                    modifier = Modifier.padding(end = 16.dp)
                )
                Text(
                    text = if (curIsCollectTraining) "Save as Training data" else "Save as Testing data",
                    color = if (curIsCollectTraining) Color(0xff55d12c) else Color.Gray,
                )
            }
            Text("Wi-Fi scanning info: $wifiScanningInfo")
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (!isSampling) {
                        val currentTimeMillis = System.currentTimeMillis()
                        if (selectedValue != "") {
                            onStartSamplingButtonClicked(
                                (selectedValue.toInt() - 1),
                                currentTimeMillis
                            )
                        } else {
                            onStartSamplingButtonClicked(null, currentTimeMillis)
                        }
                    } else {
                        onStopSamplingButtonClicked()
                    }
                },
                colors = if (isSampling) {
                    ButtonDefaults.buttonColors(containerColor = Color.Red)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(text = if (isSampling) "Stop Sampling" else "Start Sampling")
            }
        }
    }
}

@SuppressLint("UnrememberedMutableState")
@Composable
fun MarkLabelsWindow(
    modifier: Modifier = Modifier,
    //UI State
    jDMode: Boolean,
    //Location Data
    waypoints: SnapshotStateList<Offset>,
    //ButtonAction
    onAddMarkersFinishedButtonClicked: () -> Unit,
    //MapToShow
    imageBitmap: ImageBitmap,
    selectedMap: MapModels.ImageMap
) {
    // Map metadata
    val mapWidthMeters = selectedMap.metersForMapWidth // Actual map width (m)
    val mapWidthPixels = imageBitmap.width.toFloat()
    val mapHeightPixels = imageBitmap.height.toFloat()

    // Screen parameter
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val canvasCenter by derivedStateOf {
        Offset(
            canvasSize.width / 2f,
            canvasSize.height / 2f
        )
    }
    val pixelsPerMeter = mapWidthPixels / mapWidthMeters
    val metersPerPixel = mapWidthMeters / mapWidthPixels

    val maoCenterPosOffsetMeters = Offset(
        mapWidthPixels * metersPerPixel / 2f,
        mapHeightPixels * metersPerPixel / 2f
    )
    // Transition state
    var scale by remember { mutableFloatStateOf(2f) }
    var gestureRotationDegrees by remember { mutableFloatStateOf(0f) }

    var lastMarkScreenPos by remember {
        mutableStateOf(
            if (waypoints.isNotEmpty()) {
                (maoCenterPosOffsetMeters + waypoints.last()) * pixelsPerMeter
            } else {
                (maoCenterPosOffsetMeters + Offset.Zero) * pixelsPerMeter
            }
        )
    }

    var translation by remember { mutableStateOf(Offset.Zero) }
    val translationAnimation = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val lastMarkPosAnimation = remember { Animatable(lastMarkScreenPos, Offset.VectorConverter) }

    LaunchedEffect(canvasCenter) {
        val targetTranslation = canvasCenter - lastMarkScreenPos
        translationAnimation.animateTo(
            targetValue = targetTranslation,
            animationSpec = tween(1000, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(waypoints) {
        if (waypoints.isNotEmpty()) {
            waypoints.last().let { newPos ->
                lastMarkPosAnimation.animateTo(
                    targetValue = (maoCenterPosOffsetMeters + newPos) * pixelsPerMeter,
                    animationSpec = tween(500, easing = FastOutSlowInEasing)
                )
                val targetTranslation = canvasCenter - lastMarkScreenPos
                translationAnimation.animateTo(
                    targetValue = targetTranslation,
                    animationSpec = tween(500, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    LaunchedEffect(translationAnimation.value) {
        translation = translationAnimation.value
    }

    LaunchedEffect(lastMarkPosAnimation.value) {
        lastMarkScreenPos = lastMarkPosAnimation.value
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures(
                    panZoomLock = true,
                    onGesture = { centroid, pan, zoom, rotate ->
                        val newScale = (scale * zoom).coerceIn(0.2f, 5f)
                        scale = newScale
                        translation += pan
                        gestureRotationDegrees += rotate
                    }
                )

            }
    ) {
        val tertiaryContainerColor = MaterialTheme.colorScheme.tertiaryContainer
        val onTertiaryContainerColor = MaterialTheme.colorScheme.onTertiaryContainer

        // Map background
        Canvas(
            modifier = Modifier
                .fillMaxSize()
//                .background(color = Color(0xfffafafa))
                .onGloballyPositioned { coordinates ->
                    canvasSize = coordinates.size
                }
        ) {
            withTransform({
                translate(translation.x, translation.y)
                scale(scale, scale, pivot = lastMarkScreenPos)
                rotate(
                    degrees = 0.0f,
                    pivot = lastMarkScreenPos
                )
            }) {
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(imageBitmap.width, imageBitmap.height),
                    dstSize = IntSize((mapWidthPixels).toInt(), (mapHeightPixels).toInt())
                )

                waypoints.forEachIndexed { index, waypoint ->
                    val screenPos = (maoCenterPosOffsetMeters + waypoint) * pixelsPerMeter
                    drawWaypointMarker(
                        jDMode = jDMode,
                        position = screenPos,
                        scale = scale,
                        centerColor = onTertiaryContainerColor,
                        ringColor = tertiaryContainerColor,
                    )

                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            color = onTertiaryContainerColor.toArgb()
                            textSize = if (jDMode) {
                                30 / scale.sp.toPx()
                            } else {
                                140 / scale.sp.toPx()
                            }
                        }
                        val num = index + 1
                        drawText(
                            "$num",
                            screenPos.x + 20 / scale,
                            screenPos.y + 20 / scale,
                            paint
                        )
                    }
                }
            }
        }

        // Long press to add waypoints
        var longPressPosition by remember { mutableStateOf<Offset?>(null) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            longPressPosition = it
                        }
                    )
                }
        )

        fun screenToMapCoordinates(screenPos: Offset): Offset {
            // Backward translation
            val afterTranslation = screenPos - translation
            // Reverse scaling (consider reference points)
            val scaledPos = (afterTranslation - lastMarkScreenPos) / scale + lastMarkScreenPos
            // Reverse rotation (according to current mode)
            val rotationAngle = 0.0f
            val x = scaledPos.x - lastMarkScreenPos.x
            val y = scaledPos.y - lastMarkScreenPos.y
            val rotatedPos = Offset(
                x * cos(rotationAngle) - y * sin(rotationAngle) + lastMarkScreenPos.x,
                x * sin(rotationAngle) + y * cos(rotationAngle) + lastMarkScreenPos.y
            )
            // Convert to metric coordinates
            return (rotatedPos) / pixelsPerMeter - maoCenterPosOffsetMeters
        }

        longPressPosition?.let { screenPos ->
            Canvas(modifier = Modifier.size(24.dp)) {
                drawWaypointMarker(
                    jDMode = jDMode,
                    position = screenPos,
                    scale = 1.0f,
                    centerColor = tertiaryContainerColor,
                    ringColor = onTertiaryContainerColor,
                )
            }

            FilledCardExample(
                offset = screenPos,
                showingOffset = screenToMapCoordinates(screenPos),
                onConfirm = {
                    waypoints.add(screenToMapCoordinates(screenPos))
                    longPressPosition = null
                },
                onDismiss = { longPressPosition = null }
            )
        }

        FloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            onClick = {
                onAddMarkersFinishedButtonClicked()
            }
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Complete marking",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}