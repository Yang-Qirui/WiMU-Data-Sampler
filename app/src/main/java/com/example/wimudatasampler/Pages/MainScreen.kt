package com.example.wimudatasampler.Pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.example.wimudatasampler.Pages.HorizontalPage.InferenceHorizontalPage
import com.example.wimudatasampler.Pages.HorizontalPage.SamplingHorizontalPage
import com.example.wimudatasampler.MapViewModel
import com.example.wimudatasampler.R
import com.example.wimudatasampler.navigation.MainActivityDestinations
import com.example.wimudatasampler.utils.ImageUtil.Companion.getImageFolderPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    context: Context,
    navController: NavController,
    //ViewModel
    mapViewModel: MapViewModel,
    //UI State
    jDMode: Boolean,
    isCollectTraining: Boolean,
    isSampling: Boolean,
    isLocatingStarted: Boolean,
    isLoadingStarted: Boolean,
    isImuEnabled: Boolean,
    isMyStepDetectorEnabled: Boolean,
    //Sampling Data
    yaw: Float?,
    pitch: Float?,
    roll: Float?,
    numOfLabelSampling: Int?, // Start from 0
    wifiScanningInfo: String?,
    wifiSamplingCycles: Float,
    sensorSamplingCycles: Float,
    saveDirectory: String,
    //Location Data
    userHeading: Float?, // User orientation Angle (0-360)
    waypoints: SnapshotStateList<Offset>,
    imuOffset: Offset?,
    targetOffset: Offset,
    //Sampling Function
    updateWifiSamplingCycles: (Float) -> Unit,
    updateSensorSamplingCycles: (Float) -> Unit,
    updateSaveDirectory: (String) -> Unit,
    updateIsCollectTraining: (Boolean) -> Unit,
    onStartSamplingButtonClicked: (indexOfLabelToSample: Int?, startScanningTime: Long) -> Unit,
    onStopSamplingButtonClicked: () -> Unit,
    //Inference Function
    startLocating: () -> Unit,
    endLocating: () -> Unit,
    refreshLocation: () -> Unit,
    enableImu: () -> Unit,
    disableImu: () -> Unit,
    enableMyStepDetector: () -> Unit,
    disableMyStepDetector: () -> Unit,
    cumulatedStep: Int,
    uploadFlag: Int,
    uploadOffset: Offset,
    rttLatency: Float
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val styleScriptFamily = FontFamily(
                            Font(R.font.style_script, FontWeight.Normal),
                        )

                        Text(
                            text = stringResource(id = R.string.app_name),
                            style = TextStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp
                            ),
                            fontFamily = styleScriptFamily
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        navController.navigate(MainActivityDestinations.MapChoosing.route)
                    }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        navController.navigate(MainActivityDestinations.Settings.route)
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 80.dp) // Add padding to lift the buttons above the main button
            ) {
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            val titles = listOf(
                "Sampling",
                "Inference",
            )

            var state by remember { mutableIntStateOf(1) }
            val pagerState = rememberPagerState(pageCount = { titles.size })

            PrimaryTabRow(
                containerColor = MaterialTheme.colorScheme.surface,
                selectedTabIndex = state
            ) {
                titles.forEachIndexed { index, title ->
                    Tab(
                        selected = state == index,
                        onClick = {
                            state = index
                        },
                        text = {
                            Text(
                                text = title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            AppHorizontalPager(
                context = context,
                //ViewModel
                mapViewModel = mapViewModel,
                //Page State
                state = state,
                pagerState = pagerState,
                updateState = { state = pagerState.currentPage },
                //UI State
                jDMode = jDMode,
                isCollectTraining = isCollectTraining,
                isSampling = isSampling,
                isLocatingStarted = isLocatingStarted,
                isLoadingStarted = isLoadingStarted,
                isImuEnabled = isImuEnabled,
                isMyStepDetectorEnabled = isMyStepDetectorEnabled,
                //Sampling Data
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                numOfLabelSampling = numOfLabelSampling, // Start from 0
                wifiScanningInfo = wifiScanningInfo,
                wifiSamplingCycles = wifiSamplingCycles,
                sensorSamplingCycles = sensorSamplingCycles,
                saveDirectory = saveDirectory,
                //Location Data
                userHeading = userHeading, // User orientation Angle (0-360)
                waypoints = waypoints,
                imuOffset = imuOffset,
                targetOffset = targetOffset,
                //Sampling Function
                updateWifiSamplingCycles = updateWifiSamplingCycles,
                updateSensorSamplingCycles = updateSensorSamplingCycles,
                updateSaveDirectory = updateSaveDirectory,
                updateIsCollectTraining = updateIsCollectTraining,
                onStartSamplingButtonClicked = onStartSamplingButtonClicked,
                onStopSamplingButtonClicked = onStopSamplingButtonClicked,
                //Inference Function
                startLocating = startLocating,
                endLocating = endLocating,
                refreshLocation = refreshLocation,
                enableImu = enableImu,
                disableImu = disableImu,
                enableMyStepDetector = enableMyStepDetector,
                disableMyStepDetector = disableMyStepDetector,
                cumulatedStep = cumulatedStep,
                uploadFlag = uploadFlag,
                uploadOffset = uploadOffset,
                rttLatency = rttLatency
            )
        }
    }
}


@Composable
fun AppHorizontalPager(
    modifier: Modifier = Modifier,
    context: Context,
    //ViewModel
    mapViewModel: MapViewModel,
    //Page State
    state: Int,
    pagerState: PagerState,
    updateState: () -> Unit,
    //UI State
    jDMode: Boolean,
    isCollectTraining: Boolean,
    isSampling: Boolean,
    isLocatingStarted: Boolean,
    isLoadingStarted: Boolean,
    isImuEnabled: Boolean,
    isMyStepDetectorEnabled: Boolean,
    //Sampling Data
    yaw: Float?,
    pitch: Float?,
    roll: Float?,
    numOfLabelSampling: Int?, // Start from 0
    wifiScanningInfo: String?,
    wifiSamplingCycles: Float,
    sensorSamplingCycles: Float,
    saveDirectory: String,
    //Location Data
    userHeading: Float?, // User orientation Angle (0-360)
    waypoints: SnapshotStateList<Offset>,
    imuOffset: Offset?,
    targetOffset: Offset, // User's physical location (in meters)
    //Sampling Function
    updateWifiSamplingCycles: (Float) -> Unit,
    updateSensorSamplingCycles: (Float) -> Unit,
    updateSaveDirectory: (String) -> Unit,
    updateIsCollectTraining: (Boolean) -> Unit,
    onStartSamplingButtonClicked: (indexOfLabelToSample: Int?, startScanningTime: Long) -> Unit,
    onStopSamplingButtonClicked: () -> Unit,
    //Inference Function
    startLocating: () -> Unit,
    endLocating: () -> Unit,
    refreshLocation: () -> Unit,
    enableImu: () -> Unit,
    disableImu: () -> Unit,
    enableMyStepDetector: () -> Unit,
    disableMyStepDetector: () -> Unit,
    cumulatedStep: Int,
    uploadFlag: Int,
    uploadOffset: Offset,
    rttLatency: Float
) {
    LaunchedEffect(state) {
        pagerState.scrollToPage(state)
    }

    LaunchedEffect(pagerState.currentPage) {
        updateState()
    }

    val selectedMap by mapViewModel.selectedMap.collectAsState()

    HorizontalPager(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(-1f),
        state = pagerState,
        userScrollEnabled = false,
    ) { page ->
        when (page) {
            0 -> {
                SamplingHorizontalPage(
                    context = context,
                    mapViewModel = mapViewModel,
                    jDMode = jDMode,
                    isCollectTraining = isCollectTraining,
                    isSampling = isSampling,
                    yaw = yaw,
                    pitch = pitch,
                    roll = roll,
                    numOfLabelSampling = numOfLabelSampling,
                    wifiScanningInfo = wifiScanningInfo,
                    wifiSamplingCycles = wifiSamplingCycles,
                    sensorSamplingCycles = sensorSamplingCycles,
                    saveDirectory = saveDirectory,
                    waypoints = waypoints,
                    updateWifiSamplingCycles = updateWifiSamplingCycles,
                    updateSensorSamplingCycles = updateSensorSamplingCycles,
                    updateSaveDirectory = updateSaveDirectory,
                    updateIsCollectTraining = updateIsCollectTraining,
                    onStartSamplingButtonClicked = onStartSamplingButtonClicked,
                    onStopSamplingButtonClicked = onStopSamplingButtonClicked
                )
            }

            1 -> {
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
                            InferenceHorizontalPage(
                                jDMode = jDMode,
                                isLocatingStarted = isLocatingStarted,
                                isLoadingStarted = isLoadingStarted,
                                isImuEnabled = isImuEnabled,
                                isMyStepDetectorEnabled = isMyStepDetectorEnabled,
                                userHeading = userHeading,
                                waypoints = waypoints,
                                imuOffset = imuOffset,
                                targetOffset = targetOffset,
                                setLocatingStartTo = { value ->
                                    if (value) {
                                        startLocating()
                                    } else {
                                        endLocating()
                                    }
                                },
                                onRefreshButtonClicked = refreshLocation,
                                setImuEnableStateTo = { value ->
                                    if (value) {
                                        enableImu()
                                    } else {
                                        disableImu()
                                    }
                                },
                                setEnableMyStepDetector = { value ->
                                    if (value) {
                                        enableMyStepDetector()
                                    } else {
                                        disableMyStepDetector()
                                    }
                                },
                                imageBitmap = bitmap,
                                selectedMap = map,
                                cumulatedStep = cumulatedStep,
                                uploadFlag = uploadFlag,
                                uploadOffset = uploadOffset,
                                rttLatency = rttLatency
                            )
                        } ?: ImageBitmap.imageResource(R.drawable.image_placeholder)
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}