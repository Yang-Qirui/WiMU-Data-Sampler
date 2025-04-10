package com.example.wimudatasampler

import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

object UserPreferencesKeys {
    val STRIDE = floatPreferencesKey("stride")

    val BETA = floatPreferencesKey("beta")

    val INITIAL_STATE_1 = doublePreferencesKey("initial_state_1")
    val INITIAL_STATE_2 = doublePreferencesKey("initial_state_2")

    val INITIAL_COVARIANCE_1 = doublePreferencesKey("initial_covariance_1")
    val INITIAL_COVARIANCE_2 = doublePreferencesKey("initial_covariance_2")
    val INITIAL_COVARIANCE_3 = doublePreferencesKey("initial_covariance_3")
    val INITIAL_COVARIANCE_4 = doublePreferencesKey("initial_covariance_4")

    val MATRIX_Q_1 = doublePreferencesKey("matrix_q_1")
    val MATRIX_Q_2 = doublePreferencesKey("matrix_q_2")
    val MATRIX_Q_3 = doublePreferencesKey("matrix_q_3")
    val MATRIX_Q_4 = doublePreferencesKey("matrix_q_4")

    val MATRIX_R_1 = doublePreferencesKey("matrix_r_1")
    val MATRIX_R_2 = doublePreferencesKey("matrix_r_2")
    val MATRIX_R_3 = doublePreferencesKey("matrix_r_3")
    val MATRIX_R_4 = doublePreferencesKey("matrix_r_4")

    val MATRIX_R_POW_1 = intPreferencesKey("matrix_r_pow_1")
    val MATRIX_R_POW_2 = intPreferencesKey("matrix_r_pow_2")

    val SYS_NOISE = floatPreferencesKey("sys_noise")
    val OBS_NOISE = floatPreferencesKey("bos_noise")
}