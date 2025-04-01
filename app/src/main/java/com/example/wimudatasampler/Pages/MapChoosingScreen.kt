package com.example.wimudatasampler.Pages

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.wimudatasampler.MapViewModel
import com.example.wimudatasampler.R
import com.example.wimudatasampler.navigation.MainActivityDestinations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapChoosingScreen(
    modifier: Modifier = Modifier,
    context: Context,
    navController: NavController,
    mapViewModel: MapViewModel
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showAddDialog by remember { mutableStateOf(false) }
    var tempUri by remember { mutableStateOf<Uri>(Uri.EMPTY) }
    var tempName by remember { mutableStateOf("") }
    var tempMeters by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            tempUri = uri
            showAddDialog = true
        }
    }

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
                        navController.popBackStack(
                            MainActivityDestinations.Main.route,
                            inclusive = false
                        )
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBackIos,
                            contentDescription = "Back to main page"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier
                            .background(Color.Transparent)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = "Add new map"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(20.dp)
                .fillMaxSize()
        ) {
            val uiState by mapViewModel.uiStateForMaps.collectAsState()
            var showEditDialog by remember { mutableStateOf(false) }

            when (val state = uiState) {
                is MapViewModel.MapState.Loading -> {
                    CircularProgressIndicator()
                }

                is MapViewModel.MapState.Success -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(state.maps) { index, map ->
                            Card(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .background(
                                        color = if (map.isSelected) Color.Green else Color.Gray,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable {
                                        mapViewModel.selectMap(map)
                                    }
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onLongPress = {
                                                showEditDialog = true
                                            }
                                        )
                                    }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                ) {
                                    // 替换为你的图片加载逻辑
//                                    Image(
//                                        painter = rememberAsyncImagePainter(model = map.content),
//                                        contentDescription = null,
//                                        modifier = Modifier
//                                            .height(120.dp)
//                                            .fillMaxWidth()
//                                            .clip(RoundedCornerShape(8.dp))
//                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = map.name
                                    )
                                    Text(
                                        text = "${map.metersForMapWidth} meters"
                                    )
                                }
                            }
                        }
                    }
                }

                is MapViewModel.MapState.Error -> {
                    Text("Error: ${state.message}")
                }

                is MapViewModel.MapState.Empty -> {
                    Text("No maps available")
                }
            }

            if (showAddDialog) {
                AlertDialog(
                    onDismissRequest = { showAddDialog = false },
                    title = {
                        //NOTHING
                    },
                    text = {
                        Column {
                            OutlinedTextField(
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done
                                ),
                                value = tempName,
                                onValueChange = {value ->
                                    tempName = value
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(8.dp),
                                placeholder = { Text("") },
                                label = { Text("Name") }
                            )

                            OutlinedTextField(
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done
                                ),
                                value = tempMeters,
                                onValueChange = {value ->
                                    tempMeters = value
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(8.dp),
                                placeholder = { Text("") },
                                label = { Text("Meters for image width") }
                            )

                            Button(
                                onClick = {

                                },
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .align(Alignment.CenterHorizontally),
                                enabled = true
                            ) {
                                Text("SAVE")
                            }
                        }
                    },
                    confirmButton = {
                        //NOTHING
                    }
                )
            }
        }
    }
}