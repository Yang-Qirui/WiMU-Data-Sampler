package com.example.wimudatasampler.utils

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object UserPreferencesKeys {
    val STRIDE = floatPreferencesKey("stride")

    val BETA = floatPreferencesKey("beta")
    val SYS_NOISE = floatPreferencesKey("sys_noise")
    val OBS_NOISE = floatPreferencesKey("bos_noise")

    val PERIOD = floatPreferencesKey("period")

    val URL = stringPreferencesKey("url")
    val MQTT_SERVER_URL = stringPreferencesKey("mqtt_server_url")
    val API_BASE_URL = stringPreferencesKey("api_base_url")

    val AZIMUTH_OFFSET = floatPreferencesKey("azimuth_offset")

    val WAREHOUSE_NAME = stringPreferencesKey("warehouse_name")

    val USE_BLE_LOCATING =booleanPreferencesKey("use_ble_locating")

    val IS_BLUETOOTH_TIME_WINDOW_ENABLED = booleanPreferencesKey("is_bluetooth_time_window_enabled")
    val BLUETOOTH_TIME_WINDOW_SECONDS = floatPreferencesKey("bluetooth_time_window_seconds")
}