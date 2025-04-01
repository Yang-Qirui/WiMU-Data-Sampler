package com.example.wimudatasampler.navigation

sealed class MainActivityDestinations(val route: String) {
    object Main : MainActivityDestinations("main")
    object Settings : MainActivityDestinations("settings")
    object MapChoosing : MainActivityDestinations("map_choosing")
}
