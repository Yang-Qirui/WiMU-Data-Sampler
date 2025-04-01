package com.example.wimudatasampler.Pages

import android.net.wifi.WifiManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.example.wimudatasampler.HorizontalPage.InferenceHorizontalPage
import com.example.wimudatasampler.HorizontalPage.SampleHorizontalPage
import com.example.wimudatasampler.R
import com.example.wimudatasampler.navigation.MainActivityDestinations
import com.example.wimudatasampler.utils.SensorUtils
import com.example.wimudatasampler.utils.TimerUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    context: SensorUtils.SensorDataListener,
    navController: NavController,
    motionSensorManager: SensorUtils,
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
    getBeta: () -> Float,
    targetOffset: Offset?,
    navigationStarted: Boolean,
    loadingStarted: Boolean,
    startFetching: () -> Unit,
    endFetching: () -> Unit,
    imuOffset: Offset?,
    wifiOffset: Offset?,
    onRefreshButtonClicked: () ->Unit,
    setNavigationStartFalse: () -> Unit,
    setLoadingStartFalse: () -> Unit,
    estimatedStride: Float
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
                state = state,
                pagerState = pagerState,
                updateState = { state = pagerState.currentPage },
                motionSensorManager = motionSensorManager,
                wifiManager = wifiManager,
                timer = timer,
                setStartSamplingTime = setStartSamplingTime,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                isMonitoringAngles = isMonitoringAngles,
                toggleMonitoringAngles = toggleMonitoringAngles,
                waypoints = waypoints,
                changeBeta = changeBeta,
                getBeta = getBeta,
                targetOffset = targetOffset,
                startFetching = { startFetching() },
                endFetching = { endFetching() },
                imuOffset = imuOffset,
                wifiOffset = wifiOffset,
                navigationStarted = navigationStarted,
                loadingStarted = loadingStarted,
                onRefreshButtonClicked = onRefreshButtonClicked,
                setNavigationStartFalse = setNavigationStartFalse,
                setLoadingStartFalse = setLoadingStartFalse,
                estimatedStride = estimatedStride
            )
        }
    }
}


@Composable
fun AppHorizontalPager(
    modifier: Modifier = Modifier,
    context: SensorUtils.SensorDataListener,
    state: Int,
    pagerState: PagerState,
    updateState:()->Unit,
    motionSensorManager: SensorUtils,
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
    getBeta: () -> Float,
    targetOffset: Offset?,
    navigationStarted: Boolean,
    loadingStarted: Boolean,
    startFetching: () -> Unit,
    endFetching: () -> Unit,
    imuOffset: Offset?,
    wifiOffset: Offset?,
    onRefreshButtonClicked: () ->Unit,
    setNavigationStartFalse: () -> Unit,
    setLoadingStartFalse: () -> Unit,
    estimatedStride: Float
) {
    LaunchedEffect(state) {
        pagerState.scrollToPage(state)
    }

    LaunchedEffect(pagerState.currentPage) {
        updateState()
    }

    HorizontalPager(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(-1f),
        state = pagerState,
        userScrollEnabled = false,
    ) { page ->
        when (page) {
            0 -> {
                SampleHorizontalPage(
                    context = context,  // Pass the context
                    sensorManager = motionSensorManager,
                    wifiManager = wifiManager,
                    timer = timer,
                    setStartSamplingTime = setStartSamplingTime,
                    waypoints = waypoints,
                    estimatedStride = estimatedStride
                )
            }

            1 -> {
                InferenceHorizontalPage(
                    navigationStarted = navigationStarted,
                    loadingStarted = loadingStarted,
                    startFetching = startFetching,
                    endFetching = endFetching,
                    userPositionMeters = targetOffset,
                    userHeading = yaw,
                    waypoints = waypoints,
                    yaw = yaw,
                    imuOffset = imuOffset,
                    wifiOffset = wifiOffset,
                    targetOffset = targetOffset,
                    onRefreshButtonClicked = onRefreshButtonClicked,
                    setNavigationStartFalse = setNavigationStartFalse,
                    setLoadingStartFalse = setLoadingStartFalse
                )
            }
//            2 -> {
//                TrackingHorizontalPage(
//                    context = context,
//                    waypoints = waypoints,
//                    sensorManager = motionSensorManager,
//                    wifiManager = wifiManager,
//                    timer = timer
//                )
//            }
        }

    }
}