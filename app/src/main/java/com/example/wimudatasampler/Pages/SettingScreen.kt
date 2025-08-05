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
    sysNoise: Float,
    obsNoise: Float,
    period: Float,
    url:String,
    mqttServerUrl:String,
    apiBaseUrl:String,
    azimuthOffset: Float,
    updateStride: (Float) ->Unit,
    updateBeta: (Float) ->Unit,
    updateSysNoise: (Float) -> Unit,
    updateObsNoise: (Float) -> Unit,
    updatePeriod: (Float) -> Unit,
    updateUrl:(String) -> Unit,
    updateMqttServerUrl:(String) -> Unit,
    updateApiBaseUrl:(String) -> Unit,
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
        var curSysNoise by remember { mutableStateOf(sysNoise.toString()) }
        var curObsNoise by remember { mutableStateOf(obsNoise.toString()) }
        var curPeriod by remember { mutableStateOf(period.toString()) }
        var curUrl by remember { mutableStateOf(url) }
        var curMqttServerUrl by remember { mutableStateOf(mqttServerUrl) }
        var curApiBaseUrl by remember { mutableStateOf(apiBaseUrl) }
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
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    value = curUrl,
                    onValueChange = { value ->
                        curUrl = value
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
                    text = "MQTT SERVER URL",
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
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    value = curMqttServerUrl,
                    onValueChange = { value ->
                        curMqttServerUrl = value
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
                    text = "API BASE URL",
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
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    value = curApiBaseUrl,
                    onValueChange = { value ->
                        curApiBaseUrl = value
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

                            saveUserPreferences(
                                context = context,
                                stride = curStride.toFloatOrNull() ?: stride,
                                beta = curBeta.toFloatOrNull() ?: beta,
                                sysNoise = curSysNoise.toFloatOrNull() ?: sysNoise,
                                obsNoise = curObsNoise.toFloatOrNull() ?: obsNoise,
                                period = curPeriod.toFloatOrNull() ?: period,
                                url = curUrl,
                                mqttServerUrl = curMqttServerUrl,
                                apiBaseUrl = curApiBaseUrl,
                                azimuthOffset = curAzimuthOffset.toFloatOrNull()?:azimuthOffset
                            )
                            updateStride(curStride.toFloatOrNull() ?: stride)
                            updateBeta(curBeta.toFloatOrNull() ?: beta)
                            updateSysNoise(curSysNoise.toFloatOrNull() ?: sysNoise)
                            updateObsNoise(curObsNoise.toFloatOrNull() ?: obsNoise)
                            updatePeriod(curPeriod.toFloatOrNull() ?: period)
                            updateUrl(curUrl)
                            updateMqttServerUrl(curMqttServerUrl)
                            updateApiBaseUrl(curApiBaseUrl)
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
                        sysNoise != curSysNoise.toFloatOrNull() ||
                        obsNoise != curObsNoise.toFloatOrNull() ||
                        period != curPeriod.toFloatOrNull()) ||
                        url != curUrl ||
                        mqttServerUrl != curMqttServerUrl ||
                        apiBaseUrl != curApiBaseUrl ||
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
    sysNoise: Float,
    obsNoise: Float,
    period: Float,
    url: String,
    mqttServerUrl:String,
    apiBaseUrl:String,
    azimuthOffset: Float
) {
    context.dataStore.edit { preferences ->
        preferences[UserPreferencesKeys.STRIDE] = stride

        preferences[UserPreferencesKeys.BETA] = beta

        preferences[UserPreferencesKeys.SYS_NOISE] = sysNoise
        preferences[UserPreferencesKeys.OBS_NOISE] = obsNoise

        preferences[UserPreferencesKeys.PERIOD] = period

        preferences[UserPreferencesKeys.URL] = url
        preferences[UserPreferencesKeys.MQTT_SERVER_URL] = mqttServerUrl
        preferences[UserPreferencesKeys.API_BASE_URL] = apiBaseUrl

        preferences[UserPreferencesKeys.AZIMUTH_OFFSET] = azimuthOffset
    }
}