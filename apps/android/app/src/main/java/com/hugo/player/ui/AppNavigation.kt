package com.hugo.player.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hugo.player.data.DeviceIdentity
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.toString())
private fun dec(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8.toString())

fun NavHostController.openPlayer(url: String, title: String) = navigate("player/${enc(url)}/${enc(title)}")

/**
 * Fluxo: Ativação (ID/Key gerados) → Playlists (carrega dentro do card) → Home → abas.
 * Device ID/Key vêm de DeviceIdentity (não vão mais na rota).
 */
@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val nav = rememberNavController()
    val identity = remember { DeviceIdentity.get(context) }
    val startDest = remember { if (DeviceIdentity.isActivationDone(context)) "playlists" else "activation" }

    NavHost(navController = nav, startDestination = startDest) {
        composable("activation") {
            ActivationScreen(onActivated = { _, _ ->
                nav.navigate("playlists") { popUpTo("activation") { inclusive = true } }
            })
        }
        composable("playlists") {
            PlaylistScreen(
                onOpen = { _, _ -> nav.navigate("home") { launchSingleTop = true } },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("home") {
            val playlistId = LiveTvCache.playlistId ?: ""
            HomeScreen(
                playlistName = LiveTvCache.playlistName ?: "Playlist",
                onNavigate = { route ->
                    when (route) {
                        "live" -> nav.navigate("live/$playlistId")
                        "movies" -> nav.navigate("movies/$playlistId")
                        "series" -> nav.navigate("series/$playlistId")
                        "playlists" -> if (!nav.popBackStack("playlists", false)) nav.navigate("playlists")
                        else -> nav.navigate(route)
                    }
                }
            )
        }
        composable("live/{playlistId}") { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            LiveTvScreen(playlistId, identity.deviceId, identity.deviceKey, onPlay = { url, title -> nav.openPlayer(url, title) }, onBack = { nav.popBackStack() })
        }
        composable("movies/{playlistId}") { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            MoviesScreen(playlistId, onPlay = { url, title -> nav.openPlayer(url, title) }, onBack = { nav.popBackStack() })
        }
        composable("series/{playlistId}") { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            SeriesScreen(playlistId, onPlay = { url, title -> nav.openPlayer(url, title) }, onBack = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(identity.deviceId, identity.deviceKey, onBack = { nav.popBackStack() }) }
        composable("player/{url}/{title}") { backStack ->
            val url = dec(backStack.arguments?.getString("url") ?: "")
            val title = dec(backStack.arguments?.getString("title") ?: "Canal")
            PlayerScreen(url, title, onBack = { nav.popBackStack() })
        }
    }
}
