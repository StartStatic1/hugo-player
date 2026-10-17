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
                    nav.navigate("home/$deviceId/$deviceKey/$playlistId")
                },
                onAddNew = { }
            )
        }
        composable("home/{deviceId}/{deviceKey}/{playlistId}") { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId") ?: ""
            val deviceKey = backStack.arguments?.getString("deviceKey") ?: ""
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            HomeScreen(onNavigate = { route ->
                when (route) {
                    "live" -> nav.navigate("live/$deviceId/$deviceKey/$playlistId")
                    else -> nav.navigate(route)
                }
            })
        }
        composable("live/{deviceId}/{deviceKey}/{playlistId}") { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId") ?: ""
            val deviceKey = backStack.arguments?.getString("deviceKey") ?: ""
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            LiveTvScreen(
                playlistId = playlistId,
                deviceId = deviceId,
                deviceKey = deviceKey,
                onPlay = { url, title ->
                    nav.navigate("player/$url/$title")
                },
                onBack = { nav.popBackStack() }
            )
        }
        composable("movies") { MoviesScreen(onBack = { nav.popBackStack() }) }
        composable("series") { SeriesScreen(onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("player/{url}/{title}") { backStack ->
            val url = backStack.arguments?.getString("url") ?: ""
            val title = backStack.arguments?.getString("title") ?: "Canal"
            PlayerScreen(streamUrl = url, title = title, onBack = { nav.popBackStack() })
        }
    }
}