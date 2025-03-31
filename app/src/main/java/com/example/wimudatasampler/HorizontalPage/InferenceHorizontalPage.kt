package com.example.wimudatasampler.HorizontalPage


import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.wimudatasampler.FilledCardExample
import com.example.wimudatasampler.R
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class NavUiMode(val value: Int) {
    USER_POS_FIX_CENTER_DIR_FIX_UP(0),
    USER_POS_FIX_CENTER_DIR_FREE(1),
    USER_POS_FREE_CENTER_DIR_FREE(3)
}

@SuppressLint("UnrememberedMutableState")
@Composable
fun InferenceHorizontalPage(
    navigationStarted: Boolean,
    loadingStarted: Boolean,
    updateNavigationState: () -> Unit,
    startFetching: () -> Unit,
    endFetching: () -> Unit,
    userPositionMeters: Offset?, // 用户实际位置（米为单位）
    userHeading: Float, // 用户朝向角度（角度制，0-360）
    waypoints: SnapshotStateList<Offset>,
    userPosOffsetMeters: Offset,
    modifier: Modifier = Modifier,
    yaw: Float,
    imuOffset: Offset?,
    wifiOffset: Offset?,
    targetOffset: Offset?,
    onRefreshButtonClicked: () -> Unit,
    setNavigationStartFalse: () -> Unit,
    setLoadingStartFalse: () -> Unit
) {
    val imageBitmap = ImageBitmap.imageResource(R.drawable.academic_building_g)
    // Map metadata
    val mapWidthMeters = 277f // Actual map width (m)
    val mapWidthPixels = imageBitmap.width.toFloat()
    val mapHeightPixels = imageBitmap.height.toFloat()
    val mapAspectRatio = mapWidthPixels / mapHeightPixels

    // Screen parameter
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }
    val screenCenter = Offset(screenWidthPx / 2, screenHeightPx / 2)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val canvasCenter by derivedStateOf {
        Offset(
            canvasSize.width / 2f,
            canvasSize.height / 2f
        )
    }
    val pixelsPerMeter = mapWidthPixels / mapWidthMeters
    val metersPerPixel = mapWidthMeters / mapWidthPixels

    // Transition state
    var scale by remember { mutableFloatStateOf(1f) }
    var gestureRotationDegrees by remember { mutableFloatStateOf(0f) }
    var lastUserPosition by remember { mutableStateOf(Offset.Zero) }

    var userScreenPos by remember {
        mutableStateOf(
            if (userPositionMeters != null) {
                (userPosOffsetMeters + userPositionMeters) * pixelsPerMeter
            } else {
                Offset.Zero
            }

        )
    }

    var uiMode by remember { mutableIntStateOf(1) }
    var showUiModeDialog by remember { mutableStateOf(false) }

    var translation by remember { mutableStateOf(Offset.Zero) }
    val translationAnimation = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    LaunchedEffect(canvasCenter) {
        val targetTranslation = canvasCenter - userScreenPos
        translationAnimation.animateTo(
            targetValue = targetTranslation,
            animationSpec = tween(1000, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(userPositionMeters) {
        userPositionMeters?.let { newPos ->
                userScreenPos = (userPosOffsetMeters + newPos) * pixelsPerMeter
                val targetTranslation = canvasCenter - userScreenPos
                translationAnimation.animateTo(
                    targetValue = targetTranslation,
                    animationSpec = tween(1000, easing = FastOutSlowInEasing)
                )
                lastUserPosition = newPos
            }

    }

    LaunchedEffect(translationAnimation.value) {
        translation = translationAnimation.value
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures(
                    panZoomLock = true,
                    onGesture = { centroid, pan, zoom, rotate ->
                        when (uiMode) {
                            NavUiMode.USER_POS_FIX_CENTER_DIR_FIX_UP.value -> {
                                val newScale = (scale * zoom).coerceIn(0.2f, 5f)
                                scale = newScale
                                gestureRotationDegrees += rotate
                                val targetTranslation = canvasCenter - userScreenPos
                                translation = targetTranslation
                            }
                            NavUiMode.USER_POS_FIX_CENTER_DIR_FREE.value -> {
                                val newScale = (scale * zoom).coerceIn(0.2f, 5f)
                                scale = newScale
                                gestureRotationDegrees += rotate
                                val targetTranslation = canvasCenter - userScreenPos
                                translation = targetTranslation
                            }
                            else -> { // uiMode == NavUiMode.USER_POS_FREE_CENTER_DIR_FREE.value
                                val newScale = (scale * zoom).coerceIn(0.2f, 5f)
                                scale = newScale
                                translation +=  pan
                                gestureRotationDegrees += rotate
                            }
                        }
                    }
                )

            }
    ) {
        val tertiaryContainerColor = MaterialTheme.colorScheme.tertiaryContainer
        val onTertiaryContainerColor = MaterialTheme.colorScheme.onTertiaryContainer
        val secondaryContainerColor = MaterialTheme.colorScheme.secondaryContainer
        val onSecondaryContainerColor = MaterialTheme.colorScheme.onSecondaryContainer

        // Map background
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(color = Color(0xFFF0EFEA))
                .onGloballyPositioned { coordinates ->
                    canvasSize = coordinates.size
                }
        ) {
            withTransform({
                translate(translation.x, translation.y)
                scale(scale, scale, pivot = userScreenPos)
                rotate(
                    if (uiMode == NavUiMode.USER_POS_FIX_CENTER_DIR_FIX_UP.value
                        &&
                        navigationStarted
                    ) (-userHeading - 180) else 0.0f,
                    pivot = userScreenPos
                )
            }) {
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(imageBitmap.width, imageBitmap.height),
                    dstSize = IntSize((mapWidthPixels).toInt(), (mapHeightPixels).toInt())
                )

                if (uiMode == NavUiMode.USER_POS_FIX_CENTER_DIR_FIX_UP.value) {
                    if (navigationStarted) {
                        drawUserMarker(
                            position = userScreenPos,
                            headingDegrees = userHeading - 90,
                            scale = scale,
                            mainColor = onSecondaryContainerColor,
                            secondColor = secondaryContainerColor
                        )
                    } else {
                        drawUserMarker(
                            position = userScreenPos,
                            headingDegrees = userHeading + 90,
                            scale = scale,
                            mainColor = onSecondaryContainerColor,
                            secondColor = secondaryContainerColor
                        )
                    }
                } else if (uiMode == NavUiMode.USER_POS_FIX_CENTER_DIR_FREE.value) {
                    if (navigationStarted) {
                        drawUserMarker(
                            position = userScreenPos,
                            headingDegrees = userHeading,
                            scale = scale,
                            mainColor = onSecondaryContainerColor,
                            secondColor = secondaryContainerColor
                        )
                    } else {
                        drawUserMarker(
                            position = userScreenPos,
                            headingDegrees = userHeading + 90,
                            scale = scale,
                            mainColor = onSecondaryContainerColor,
                            secondColor = secondaryContainerColor
                        )
                    }
                } else { // uiMode == NavUiMode.USER_POS_FREE_CENTER_DIR_FREE.value
                    if (navigationStarted) {
                        drawUserMarker(
                            position = userScreenPos,
                            headingDegrees = userHeading,
                            scale = scale,
                            mainColor = onSecondaryContainerColor,
                            secondColor = secondaryContainerColor
                        )
                    } else {
                        drawUserMarker(
                            position = userScreenPos,
                            headingDegrees = userHeading + 90,
                            scale = scale,
                            mainColor = onSecondaryContainerColor,
                            secondColor = secondaryContainerColor
                        )
                    }
                }

                waypoints.forEach { waypoint ->
                    val screenPos = (userPosOffsetMeters + waypoint) * pixelsPerMeter
                    drawWaypointMarker(
                        position = screenPos,
                        scale = scale,
                        centerColor = onTertiaryContainerColor,
                        ringColor = tertiaryContainerColor,
                    )
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
            val scaledPos = (afterTranslation - userScreenPos) / scale + userScreenPos
            // Reverse rotation (according to current mode)
            val rotationAngle = if (uiMode == NavUiMode.USER_POS_FIX_CENTER_DIR_FIX_UP.value && navigationStarted) {
                -Math.toRadians((-userHeading + 180).toDouble()).toFloat()
            } else {
                0f
            }
            val x = scaledPos.x - userScreenPos.x
            val y = scaledPos.y - userScreenPos.y
            val rotatedPos = Offset(
                x * cos(rotationAngle) - y * sin(rotationAngle) + userScreenPos.x,
                x * sin(rotationAngle) + y * cos(rotationAngle) + userScreenPos.y
            )
            // Convert to metric coordinates
            return (rotatedPos - userScreenPos) / pixelsPerMeter
        }

        longPressPosition?.let { screenPos ->
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

        Text(
            modifier = Modifier
                .align(Alignment.TopCenter),
            text = when {
                !navigationStarted -> "Ready to go"
                else -> "yaw ${yaw.roundToInt()}, ${imuOffset?.x?.roundToInt()}, ${imuOffset?.y?.roundToInt()}, ${wifiOffset?.x?.roundToInt()}, ${wifiOffset?.y?.roundToInt()}, ${targetOffset?.x?.roundToInt()}, ${targetOffset?.y?.roundToInt()}"
            }
        )

        IconButton(
            onClick = { showUiModeDialog = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 6.dp, end = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.MyLocation,
                contentDescription = "Choose UI display method",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (navigationStarted && !loadingStarted) {
            FloatingActionButton(
                modifier = Modifier
                    .padding(25.dp)
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 80.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                onClick = { onRefreshButtonClicked() }
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        FloatingActionButton(
            modifier = Modifier
                .padding(25.dp)
                .align(Alignment.BottomEnd),
            containerColor =
            if (!navigationStarted && loadingStarted) {
                colorResource(id = R.color.button_container_green)
            } else if (navigationStarted && !loadingStarted){
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            onClick = {
                if (!navigationStarted && !loadingStarted) {
                    startFetching()
                } else if (navigationStarted && !loadingStarted) {
                    endFetching()
                    setNavigationStartFalse()
                    setLoadingStartFalse()
                }
            }
        ) {
            if (!navigationStarted && !loadingStarted) {
                Icon(
                    Icons.Outlined.Navigation,
                    contentDescription = "Navigation",
                )
            } else if (!navigationStarted && loadingStarted){
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = colorResource(id = R.color.button_content_green),
                    strokeWidth = 3.dp
                )
            } else if (navigationStarted && !loadingStarted) {
                Icon(
                    Icons.Outlined.Cancel,
                    contentDescription = "Stop navigation",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showUiModeDialog) {
        AlertDialog(
            onDismissRequest = { showUiModeDialog = false },
            title = {},
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (uiMode == NavUiMode.USER_POS_FIX_CENTER_DIR_FIX_UP.value),
                            onClick = {
                                val targetTranslation = canvasCenter - userScreenPos
                                translation = targetTranslation
                                uiMode = NavUiMode.USER_POS_FIX_CENTER_DIR_FIX_UP.value
                                showUiModeDialog = false
                            }
                        )
                        Text(text = "Marker pos fix and dir up")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (uiMode == NavUiMode.USER_POS_FIX_CENTER_DIR_FREE.value),
                            onClick = {
                                val targetTranslation = canvasCenter - userScreenPos
                                translation = targetTranslation
                                uiMode = NavUiMode.USER_POS_FIX_CENTER_DIR_FREE.value
                                showUiModeDialog = false
                            }
                        )
                        Text(
                            text = "Marker pos fix and dir free"
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = (uiMode == NavUiMode.USER_POS_FREE_CENTER_DIR_FREE.value),
                            onClick = {
                                uiMode = NavUiMode.USER_POS_FREE_CENTER_DIR_FREE.value
                                showUiModeDialog = false
                            }
                        )
                        Text(
                            text = "Marker pos free and dir free"
                        )
                    }
                }
            },
            confirmButton = {
                //NOTHING
            }
        )
    }
}


private fun DrawScope.drawUserMarker(
    position: Offset,
    headingDegrees: Float,
    scale: Float,
    mainColor:Color,
    secondColor: Color
) {
    val headingRadians = Math.toRadians(headingDegrees.toDouble()).toFloat()
    val direction = Offset(
        x = -cos(headingRadians),
        y = -sin(headingRadians)
    ) * 30f
    val centerX = position.x
    val centerY = position.y
    // Radius
    val innerRadius = 30f/scale
    val outerRadius = 35f/scale
    // Draw triangle for direction
    val originAngle = Math.toRadians(120f.toDouble())
    val directionAngle = Math.toRadians((headingDegrees+180).toDouble())

    // Calculate the points of the triangle based on direction
    val triangleHeight = innerRadius / cos(originAngle / 2).toFloat()
    val tipX = centerX + triangleHeight * cos(directionAngle).toFloat()
    val tipY = centerY + triangleHeight * sin(directionAngle).toFloat()

    val leftBaseX = centerX + innerRadius * cos(directionAngle + originAngle / 2).toFloat()
    val leftBaseY = centerY + innerRadius * sin(directionAngle + originAngle / 2).toFloat()

    val rightBaseX = centerX + innerRadius * cos(directionAngle - originAngle / 2).toFloat()
    val rightBaseY = centerY + innerRadius * sin(directionAngle - originAngle / 2).toFloat()

    // Create a path for the triangle
    val path = Path().apply {
        moveTo(tipX, tipY) // Move to tip of the triangle
        lineTo(leftBaseX, leftBaseY) // Left bottom corner
        lineTo(rightBaseX, rightBaseY) // Right bottom corner
        close() // Close the path to form a triangle
    }
    drawPath(
        path = path,
        color = mainColor
    )

    // Draw the triangle outline
    drawPath(
        path = path,
        color = secondColor,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = outerRadius - innerRadius)
    )

    // Outer glow
    drawCircle(
        color = secondColor,
        radius = outerRadius, // Larger radius for greater glow
        center = Offset(centerX, centerY)
    )
    // Inner glow
    drawCircle(
        color = mainColor, // Glow color: #77B6EA
        radius = innerRadius, // Radius of glow
        center = Offset(centerX, centerY)
    )
}

private fun DrawScope.drawWaypointMarker(position: Offset, scale:Float, centerColor: Color,ringColor:Color) {
    drawCircle(
        color = ringColor,
        center = position,
        radius = 40/scale.dp.toPx()
    )
    drawCircle(
        color = centerColor,
        center = position,
        radius = 20/scale.dp.toPx()
    )
}

