package com.hugo.player.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun AppNavigation() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "activation") {
        composable("activation") {
            ActivationScreen(onActivated = { deviceId, deviceKey ->
                nav.navigate("playlists/$deviceId/$deviceKey") {
                    popUpTo("activation") { inclusive = true }
                }
            })
        }
        composable("playlists/{deviceId}/{deviceKey}") { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId") ?: ""
            val deviceKey = backStack.arguments?.getString("deviceKey") ?: ""
            PlaylistScreen(
                deviceId = deviceId,
                deviceKey = deviceKey,
                onSelect = { playlistId ->
                    nav.navigate("home/$playlistId")
                },
                onAddNew = { /* open site or show message */ }
            )
        }
        composable("home/{playlistId}") {
            HomeScreen(onNavigate = { route ->
                nav.navigate(route)
            })
        }
        composable("movies") { MoviesScreen(onBack = { nav.popBackStack() }) }
        composable("series") { SeriesScreen(onBack = { nav.popBackStack() }) }
        composable("live") { LiveTvScreen(onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}