package com.hugo.player.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf("home") }

    when (currentScreen) {
        "home" -> HomeScreen(onNavigate = { currentScreen = it })
        "live" -> LiveTvScreen(onBack = { currentScreen = "home" })
        "movies" -> MoviesScreen(onBack = { currentScreen = "home" })
        "series" -> SeriesScreen(onBack = { currentScreen = "home" })
        "settings" -> SettingsScreen(onBack = { currentScreen = "home" })
        else -> HomeScreen(onNavigate = { currentScreen = it })
    }
}