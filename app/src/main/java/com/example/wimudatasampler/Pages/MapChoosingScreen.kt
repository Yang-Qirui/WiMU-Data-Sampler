package com.example.wimudatasampler.Pages

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.example.wimudatasampler.DataClass.MapModels
import com.example.wimudatasampler.MapViewModel
import com.example.wimudatasampler.R
import com.example.wimudatasampler.navigation.MainActivityDestinations
import com.example.wimudatasampler.utils.ImageUtil.Companion.getImageFolderPath
import com.example.wimudatasampler.utils.ImageUtil.Companion.saveImageToExternalStorage
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapChoosingScreen(
    modifier: Modifier = Modifier,
    context: Context,
    navController: NavController,
    mapViewModel: MapViewModel
) {
    val styleScriptFamily = FontFamily(
        Font(R.font.style_script, FontWeight.Normal),
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showAddDialog by remember { mutableStateOf(false) }
    var tempContent by remember { mutableStateOf("") }
    var tempName by remember { mutableStateOf("") }
    var tempMeters by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val fileName = processSelectedUri(context = context, uri = it)
            if (fileName.isNotEmpty()) {
                tempContent = fileName
                showAddDialog = true
            }
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
                        onClick = { imagePickerLauncher.launch("image/*") },
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

            when (val state = uiState) {
                is MapViewModel.MapState.Loading -> {
                    CircularProgressIndicator()
                }

                is MapViewModel.MapState.Success -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (state.maps.isNotEmpty()) {
                            itemsIndexed(state.maps) { index, map ->
                                var showDeleteConfirmation by remember { mutableStateOf(false) }
                                var deleteButtonPosition by remember { mutableStateOf(Offset.Zero) }

                                Card(
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onLongPress = { offset ->
                                                    showDeleteConfirmation = true
                                                    deleteButtonPosition = offset
                                                },
                                                onTap = {
                                                    mapViewModel.updateSelectMap(map)
                                                }
                                            )
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (map.isSelected) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        }
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp)
                                    ) {
                                        val folderPath =
                                            getImageFolderPath(context)
                                        val fullPath = File(
                                            folderPath,
                                            map.content
                                        ).absolutePath

                                        val painter =
                                            rememberAsyncImagePainter(
                                                ImageRequest
                                                    .Builder(LocalContext.current)
                                                    .data(data = File(fullPath))
                                                    .apply(block = fun ImageRequest.Builder.() {
                                                        size(Size.ORIGINAL)
                                                        placeholder(R.drawable.image_placeholder)
                                                        error(R.drawable.image_placeholder)
                                                        crossfade(true)
                                                    }).build()
                                            )
                                        Box(
                                            modifier = Modifier
                                                .zIndex(-1.0f)
                                                .fillMaxWidth()
                                                .height(120.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.outline,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                        ) {
                                            Image(
                                                painter = painter,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(RoundedCornerShape(8.dp)),
                                                contentScale = ContentScale.Fit
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            modifier = Modifier.align(Alignment.CenterHorizontally),
                                            text = map.name
                                        )
                                        Text(
                                            modifier = Modifier.align(Alignment.CenterHorizontally),
                                            text = "${map.metersForMapWidth} meters"
                                        )
                                    }
                                }

                                if (showDeleteConfirmation) {
                                    Card(
                                        modifier = Modifier
                                            .wrapContentSize()
                                            .offset {
                                                IntOffset(
                                                    deleteButtonPosition.x.toInt(),
                                                    deleteButtonPosition.y.toInt()
                                                )
                                            }
                                            .padding(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .background(
                                                    color = MaterialTheme.colorScheme.errorContainer,
                                                    shape = CircleShape
                                                )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .clickable {
                                                        mapViewModel.deleteMap(map)
                                                        showDeleteConfirmation = false
                                                    }
                                                    .background(
                                                        color = MaterialTheme.colorScheme.errorContainer,
                                                        shape = CircleShape
                                                    )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Delete,
                                                    contentDescription = "Delete",
                                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                                Text(
                                                    modifier = Modifier.padding(4.dp),
                                                    text = "Delete"
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))

                                            Row(
                                                modifier = Modifier
                                                    .clickable {
                                                        showDeleteConfirmation = false
                                                    }
                                                    .background(
                                                        color = MaterialTheme.colorScheme.errorContainer,
                                                        shape = CircleShape
                                                    )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Cancel,
                                                    contentDescription = "Cancel",
                                                    tint = MaterialTheme.colorScheme.onErrorContainer,

                                                    )
                                                Text(
                                                    modifier = Modifier.padding(4.dp),
                                                    text = "Cancel"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                is MapViewModel.MapState.Error -> {
                    Text("Error: ${state.message}")
                }

                is MapViewModel.MapState.Empty -> {
                    Text(
                        text = "No maps available",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        fontFamily = styleScriptFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
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
                                    imeAction = ImeAction.Done
                                ),
                                value = tempName,
                                onValueChange = {value ->
                                    tempName = value
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                placeholder = { Text("") },
                                label = { Text("Meters for image width") }
                            )

                            Button(
                                onClick = {
                                    val newMap = MapModels.ImageMap(
                                        name = tempName,
                                        metersForMapWidth = tempMeters.toFloatOrNull() ?: 0f,
                                        content = tempContent
                                    )
                                    mapViewModel.saveMap(newMap)
                                    tempName = ""
                                    tempContent = ""
                                    tempMeters = ""
                                    showAddDialog = false
                                },
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .align(Alignment.CenterHorizontally),
                                enabled = tempName.isNotEmpty() && tempMeters.isNotEmpty()
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

private fun processSelectedUri(context: Context, uri: Uri): String {
    return try {
        val timestamp = System.currentTimeMillis()
        val fileName = "$timestamp.png"
        saveImageToExternalStorage(
            context = context,
            uri = uri,
            fileName = fileName
        )
        fileName
    } catch (e: Exception) {
        Log.e("ImageSave", "Error saving image", e)
        ""
    }
}