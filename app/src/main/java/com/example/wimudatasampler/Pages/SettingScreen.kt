package com.example.wimudatasampler.Pages

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.coroutineScope
import androidx.navigation.NavController
import com.example.wimudatasampler.R
import com.example.wimudatasampler.utils.UserPreferencesKeys
import com.example.wimudatasampler.dataStore
import com.example.wimudatasampler.navigation.MainActivityDestinations
import kotlinx.coroutines.launch
import kotlin.math.pow

@SuppressLint("UnrememberedMutableState")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    modifier: Modifier = Modifier,
    context: Context,
    navController: NavController,
    stride: Float,
    beta: Float,
    initialState: DoubleArray,
    initialCovariance:  Array<DoubleArray>,
    matrixQ: Array<DoubleArray>,
    matrixR: Array<DoubleArray>,
    matrixRPowOne: Int,
    matrixRPowTwo: Int,
    sysNoise: Float,
    obsNoise: Float,
    period: Float,
    fetchUrl:String,
    resetUrl:String,
    azimuthOffset: Float,
    updateStride: (Float) ->Unit,
    updateBeta: (Float) ->Unit,
    updateInitialState: (DoubleArray) -> Unit,
    updateInitialCovariance: (Array<DoubleArray>) -> Unit,
    updateMatrixQ: (Array<DoubleArray>) -> Unit,
    updateMatrixR: (Array<DoubleArray>) -> Unit,
    updateMatrixRPowOne: (Int) ->Unit,
    updateMatrixRPowTwo: (Int) ->Unit,
    updateFullMatrixR: (Array<DoubleArray>) -> Unit,
    updateSysNoise: (Float) -> Unit,
    updateObsNoise: (Float) -> Unit,
    updatePeriod: (Float) -> Unit,
    updateFetchUrl:(String) -> Unit,
    updateResetUrl:(String) -> Unit,
    updateAzimuthOffset:(Float) -> Unit
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
                        onClick = { /* ... */ },
                        modifier = Modifier
                            .background(Color.Transparent),
                        enabled = false
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBackIos,
                            contentDescription = "",
                            tint = Color.Transparent
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->

        var curStride by remember { mutableStateOf(stride.toString()) }
        var curBeta by remember { mutableStateOf(beta.toString()) }
        var curInitialState1 by remember { mutableStateOf(initialState[0].toString()) }
        var curInitialState2 by remember { mutableStateOf(initialState[1].toString()) }
        var curMatrixQ1 by remember { mutableStateOf(matrixQ[0][0].toString()) }
        var curMatrixQ2 by remember { mutableStateOf(matrixQ[0][1].toString()) }
        var curMatrixQ3 by remember { mutableStateOf(matrixQ[1][0].toString()) }
        var curMatrixQ4 by remember { mutableStateOf(matrixQ[1][1].toString()) }
        var curInitialCovariance1 by remember { mutableStateOf(initialCovariance[0][0].toString()) }
        var curInitialCovariance2 by remember { mutableStateOf(initialCovariance[0][1].toString()) }
        var curInitialCovariance3 by remember { mutableStateOf(initialCovariance[1][0].toString()) }
        var curInitialCovariance4 by remember { mutableStateOf(initialCovariance[1][1].toString()) }
        var curMatrixR1 by remember { mutableStateOf(matrixR[0][0].toString()) }
        var curMatrixR2 by remember { mutableStateOf(matrixR[0][1].toString()) }
        var curMatrixR3 by remember { mutableStateOf(matrixR[1][0].toString()) }
        var curMatrixR4 by remember { mutableStateOf(matrixR[1][1].toString()) }
        var curMatrixRPowOne by remember { mutableStateOf(matrixRPowOne.toString()) }
        var curMatrixRPowTwo by remember { mutableStateOf(matrixRPowTwo.toString()) }
        var curSysNoise by remember { mutableStateOf(sysNoise.toString()) }
        var curObsNoise by remember { mutableStateOf(obsNoise.toString()) }
        var curPeriod by remember { mutableStateOf(period.toString()) }
        var curFetchUrl by remember { mutableStateOf(fetchUrl) }
        var curResetUrl by remember { mutableStateOf(resetUrl) }
        var curAzimuthOffset by remember { mutableStateOf(azimuthOffset.toString()) }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            val styleScriptFamily = FontFamily(
                Font(R.font.style_script, FontWeight.Normal),
            )

            Column(
                modifier = Modifier
                    .padding(8.dp)
            ) {
                Text(
                    text = "Azimuth Offset",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    fontFamily = styleScriptFamily,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curAzimuthOffset,
                    onValueChange = { value ->
                        curAzimuthOffset = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("") }
                )
            }

            Column(
                modifier = Modifier
                    .padding(8.dp)
            ) {
                Text(
                    text = "Fetch URL",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    fontFamily = styleScriptFamily,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    value = curFetchUrl,
                    onValueChange = { value ->
                        curFetchUrl = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("") }
                )
            }

            Column(
                modifier = Modifier
                    .padding(8.dp)
            ) {
                Text(
                    text = "Reset URL",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    fontFamily = styleScriptFamily,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    value = curResetUrl,
                    onValueChange = { value ->
                        curResetUrl = value
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("") }
                )
            }

            Row(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "stride",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        fontFamily = styleScriptFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        value = curStride,
                        onValueChange = { value ->
                            curStride = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("") }
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "beta",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        fontFamily = styleScriptFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        value = curBeta,
                        onValueChange = { value ->
                            curBeta = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("") }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    Text(
                        text = "initial state",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        fontFamily = styleScriptFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp)
                    )
                    Row {
                        OutlinedTextField(
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            value = curInitialState1,
                            onValueChange = {value ->
                                curInitialState1 = value
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(8.dp)
                        )

                        OutlinedTextField(
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            value = curInitialState2,
                            onValueChange = {value ->
                                curInitialState2 = value
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(8.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "initial covariance",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                fontFamily = styleScriptFamily,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp)
            )

            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curInitialCovariance1,
                    onValueChange = {value ->
                        curInitialCovariance1 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curInitialCovariance2,
                    onValueChange = {value ->
                        curInitialCovariance2 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
            }
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curInitialCovariance3,
                    onValueChange = {value ->
                        curInitialCovariance3 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curInitialCovariance4,
                    onValueChange = {value ->
                        curInitialCovariance4 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Q",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                fontFamily = styleScriptFamily,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp)
            )

            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curMatrixQ1,
                    onValueChange = {value ->
                        curMatrixQ1 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curMatrixQ2,
                    onValueChange = {value ->
                        curMatrixQ2 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
            }
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curMatrixQ3,
                    onValueChange = {value ->
                        curMatrixQ3 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curMatrixQ4,
                    onValueChange = {value ->
                        curMatrixQ4 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "R",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                fontFamily = styleScriptFamily,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp)
            )

            Row(Modifier.fillMaxWidth()) {
                Row(Modifier.weight(1f)) {
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        value = curMatrixR1,
                        onValueChange = {value ->
                            curMatrixR1 = value
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp),
                        placeholder = { Text("") }
                    )
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        value = curMatrixRPowOne,
                        onValueChange = {value ->
                            curMatrixRPowOne = value
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp),
                        placeholder = { Text("") },
                        label = { Text("pow") }
                    )
                }

                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curMatrixR2,
                    onValueChange = {value ->
                        curMatrixR2 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
            }
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    value = curMatrixR3,
                    onValueChange = {value ->
                        curMatrixR3 = value
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    placeholder = { Text("") }
                )
                Row(Modifier.weight(1f)) {
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        value = curMatrixR4,
                        onValueChange = {value ->
                            curMatrixR4 = value
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp),
                        placeholder = { Text("") }
                    )
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        value = curMatrixRPowTwo,
                        onValueChange = {value ->
                            curMatrixRPowTwo = value
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(8.dp),
                        placeholder = { Text("") },
                        label = { Text("pow") }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "sys noise",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        fontFamily = styleScriptFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        value = curSysNoise,
                        onValueChange = { value ->
                            curSysNoise = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("") }
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "obs noise",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        fontFamily = styleScriptFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        value = curObsNoise,
                        onValueChange = { value ->
                            curObsNoise = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("") }
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "period",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        fontFamily = styleScriptFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        value = curPeriod,
                        onValueChange = { value ->
                            curPeriod = value
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("") }
                    )
                }
            }

            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val lifecycleScope = remember { lifecycle.coroutineScope }

            Button(
                onClick = {
                    lifecycleScope.launch {
                        try {
                            val tempInitialState = doubleArrayOf(
                                curInitialState1.toDoubleOrNull()?: initialState[0],
                                curInitialState1.toDoubleOrNull()?: initialState[1]
                            )
                            val tempInitialCovariance = arrayOf(
                                doubleArrayOf(
                                    curInitialCovariance1.toDoubleOrNull()?:initialCovariance[0][0],
                                    curInitialCovariance2.toDoubleOrNull()?:initialCovariance[0][1],
                                ),
                                doubleArrayOf(
                                    curInitialCovariance3.toDoubleOrNull()?:initialCovariance[1][0],
                                    curInitialCovariance4.toDoubleOrNull()?:initialCovariance[1][1]
                                ),
                            )
                            val tempCurMatrixQ = arrayOf(
                                doubleArrayOf(
                                    curMatrixQ1.toDoubleOrNull()?:matrixQ[0][0],
                                    curMatrixQ2.toDoubleOrNull()?:matrixQ[0][1],
                                ),
                                doubleArrayOf(
                                    curMatrixQ3.toDoubleOrNull()?:matrixQ[1][0],
                                    curMatrixQ4.toDoubleOrNull()?:matrixQ[1][1]
                                ),
                            )
                            val tempCurMatrixR = arrayOf(
                                doubleArrayOf(
                                    curMatrixR1.toDoubleOrNull()?:matrixR[0][0],
                                    curMatrixR2.toDoubleOrNull()?:matrixR[0][1],
                                ),
                                doubleArrayOf(
                                    curMatrixR3.toDoubleOrNull()?:matrixR[1][0],
                                    curMatrixR4.toDoubleOrNull()?:matrixR[1][1]
                                ),
                            )
                            val tempFullCurMatrixR = arrayOf(
                                doubleArrayOf(
                                    tempCurMatrixR[0][0].pow(curMatrixRPowOne.toIntOrNull()?:matrixRPowOne),
                                    tempCurMatrixR[0][1],
                                ),
                                doubleArrayOf(
                                    tempCurMatrixR[1][0],
                                    tempCurMatrixR[1][1].pow(curMatrixRPowTwo.toIntOrNull()?:matrixRPowTwo),
                                ),
                            )
                            saveUserPreferences(
                                context = context,
                                stride = curStride.toFloatOrNull() ?: stride,
                                beta = curBeta.toFloatOrNull() ?: beta,
                                initialState = tempInitialState,
                                initialCovariance = tempInitialCovariance,
                                matrixQ = tempCurMatrixQ,
                                matrixR = tempCurMatrixR,
                                matrixRPowOne = curMatrixRPowOne.toIntOrNull() ?: matrixRPowOne,
                                matrixRPowTwo = curMatrixRPowTwo.toIntOrNull() ?: matrixRPowTwo,
                                sysNoise = curSysNoise.toFloatOrNull() ?: sysNoise,
                                obsNoise = curObsNoise.toFloatOrNull() ?: obsNoise,
                                period = curPeriod.toFloatOrNull() ?: period,
                                fetchUrl = curFetchUrl,
                                resetUrl = curResetUrl,
                                azimuthOffset = curAzimuthOffset.toFloatOrNull()?:azimuthOffset
                            )
                            updateStride(curStride.toFloatOrNull() ?: stride)
                            updateBeta(curBeta.toFloatOrNull() ?: beta)
                            updateInitialState(tempInitialState)
                            updateInitialCovariance(tempInitialCovariance)
                            updateMatrixQ(tempCurMatrixQ)
                            updateMatrixR(tempCurMatrixR)
                            updateMatrixRPowOne(curMatrixRPowOne.toIntOrNull() ?: matrixRPowOne)
                            updateMatrixRPowTwo(curMatrixRPowTwo.toIntOrNull() ?: matrixRPowTwo)
                            updateFullMatrixR(tempFullCurMatrixR)
                            updateSysNoise(curSysNoise.toFloatOrNull() ?: sysNoise)
                            updateObsNoise(curObsNoise.toFloatOrNull() ?: obsNoise)
                            updatePeriod(curPeriod.toFloatOrNull() ?: period)
                            updateFetchUrl(curFetchUrl)
                            updateResetUrl(curResetUrl)
                            updateAzimuthOffset(curAzimuthOffset.toFloatOrNull() ?: azimuthOffset)
                        } catch (e: Exception) {
                            Log.e("SAVE", "Save failed: ${e.message}")
                        }
                    }
                },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .align(Alignment.CenterHorizontally),
                enabled = (stride != curStride.toFloatOrNull() ||
                        beta != curBeta.toFloatOrNull() ||
                        initialCovariance[0][0] != curInitialCovariance1.toDoubleOrNull() ||
                        initialCovariance[0][1] != curInitialCovariance2.toDoubleOrNull() ||
                        initialCovariance[1][0] != curInitialCovariance3.toDoubleOrNull() ||
                        initialCovariance[1][1] != curInitialCovariance4.toDoubleOrNull() ||
                        matrixQ[0][0] != curMatrixQ1.toDoubleOrNull() ||
                        matrixQ[0][1] != curMatrixQ2.toDoubleOrNull() ||
                        matrixQ[1][0] != curMatrixQ3.toDoubleOrNull() ||
                        matrixQ[1][1] != curMatrixQ4.toDoubleOrNull() ||
                        matrixR[0][0] != curMatrixR1.toDoubleOrNull() ||
                        matrixR[0][1] != curMatrixR2.toDoubleOrNull() ||
                        matrixR[1][0] != curMatrixR3.toDoubleOrNull() ||
                        matrixR[1][1] != curMatrixR4.toDoubleOrNull() ||
                        matrixRPowOne != curMatrixRPowOne.toIntOrNull() ||
                        matrixRPowTwo != curMatrixRPowTwo.toIntOrNull() ||
                        sysNoise != curSysNoise.toFloatOrNull() ||
                        obsNoise != curObsNoise.toFloatOrNull() ||
                        period != curPeriod.toFloatOrNull()) ||
                        fetchUrl != curFetchUrl ||
                        resetUrl != curResetUrl ||
                        azimuthOffset != curAzimuthOffset.toFloatOrNull()
            ) {
                Text("SAVE")
            }
        }
    }
}

suspend fun saveUserPreferences(
    context: Context,
    stride: Float,
    beta: Float,
    initialState: DoubleArray,
    initialCovariance: Array<DoubleArray>,
    matrixQ: Array<DoubleArray>,
    matrixR: Array<DoubleArray>,
    matrixRPowOne: Int,
    matrixRPowTwo: Int,
    sysNoise: Float,
    obsNoise: Float,
    period: Float,
    fetchUrl: String,
    resetUrl: String,
    azimuthOffset: Float
) {
    context.dataStore.edit { preferences ->
        preferences[UserPreferencesKeys.STRIDE] = stride

        preferences[UserPreferencesKeys.BETA] = beta

        preferences[UserPreferencesKeys.INITIAL_STATE_1] = initialState[0]
        preferences[UserPreferencesKeys.INITIAL_STATE_2] = initialState[1]

        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_1] = initialCovariance[0][0]
        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_2] = initialCovariance[0][1]
        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_3] = initialCovariance[1][0]
        preferences[UserPreferencesKeys.INITIAL_COVARIANCE_4] = initialCovariance[1][1]

        preferences[UserPreferencesKeys.MATRIX_Q_1] = matrixQ[0][0]
        preferences[UserPreferencesKeys.MATRIX_Q_2] = matrixQ[0][1]
        preferences[UserPreferencesKeys.MATRIX_Q_3] = matrixQ[1][0]
        preferences[UserPreferencesKeys.MATRIX_Q_4] = matrixQ[1][1]

        preferences[UserPreferencesKeys.MATRIX_R_1] = matrixR[0][0]
        preferences[UserPreferencesKeys.MATRIX_R_2] = matrixR[0][1]
        preferences[UserPreferencesKeys.MATRIX_R_3] = matrixR[1][0]
        preferences[UserPreferencesKeys.MATRIX_R_4] = matrixR[1][1]

        preferences[UserPreferencesKeys.MATRIX_R_POW_1] = matrixRPowOne
        preferences[UserPreferencesKeys.MATRIX_R_POW_2] = matrixRPowTwo

        preferences[UserPreferencesKeys.SYS_NOISE] = sysNoise
        preferences[UserPreferencesKeys.OBS_NOISE] = obsNoise

        preferences[UserPreferencesKeys.PERIOD] = period

        preferences[UserPreferencesKeys.FETCH_URL] = fetchUrl
        preferences[UserPreferencesKeys.RESET_URL] = resetUrl
        preferences[UserPreferencesKeys.AZIMUTH_OFFSET] = azimuthOffset
    }
}