package com.hugo.player.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hugo.player.data.AppSettings
import com.hugo.player.data.CatalogRepository
import com.hugo.player.player.PlaybackSession
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val nav = rememberNavController()
    val prefs = remember { context.getSharedPreferences("hugo", Context.MODE_PRIVATE) }

    val savedDeviceId = prefs.getString("deviceId", null)
    val savedDeviceKey = prefs.getString("deviceKey", null)
    val hasActivation = !savedDeviceId.isNullOrEmpty() && !savedDeviceKey.isNullOrEmpty()

    val startDest = if (hasActivation) "playlists/$savedDeviceId/$savedDeviceKey" else "activation"
    // Abre a última playlist automaticamente se houver cache salvo (uma vez por sessão)
    var autoOpened by rememberSaveable { mutableStateOf(false) }

    NavHost(navController = nav, startDestination = startDest) {
        composable("activation") {
            ActivationScreen(onActivated = { deviceId, deviceKey ->
                prefs.edit()
                    .putString("deviceId", deviceId)
                    .putString("deviceKey", deviceKey)
                    .apply()
                nav.navigate("playlists/$deviceId/$deviceKey") {
                    popUpTo("activation") { inclusive = true }
                }
            })
        }
        composable("playlists/{deviceId}/{deviceKey}") { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId") ?: ""
            val deviceKey = backStack.arguments?.getString("deviceKey") ?: ""
            LaunchedEffect(Unit) {
                if (autoOpened) return@LaunchedEffect
                autoOpened = true
                val lastId = AppSettings.lastPlaylistId
                if (AppSettings.autoOpenLastPlaylist && lastId != null && CatalogRepository.hasDiskCache(lastId)) {
                    val name = Uri.encode(AppSettings.lastPlaylistName ?: "Playlist")
                    nav.navigate("loading/$deviceId/$deviceKey/$lastId/$name")
                }
            }
            PlaylistScreen(
                deviceId = deviceId,
                deviceKey = deviceKey,
                onSelect = { playlistId, playlistName ->
                    // Se já tem cache dessa playlist, vai direto pra home
                    if (CatalogRepository.cached(playlistId) != null) {
                        nav.navigate("home/$deviceId/$deviceKey/$playlistId")
                    } else {
                        nav.navigate("loading/$deviceId/$deviceKey/$playlistId/${Uri.encode(playlistName)}")
                    }
                },
                onAddNew = { }
            )
        }
        composable("loading/{deviceId}/{deviceKey}/{playlistId}/{playlistName}") { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId") ?: ""
            val deviceKey = backStack.arguments?.getString("deviceKey") ?: ""
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            val playlistName = backStack.arguments?.getString("playlistName") ?: "Playlist"
            LoadingScreen(
                playlistId = playlistId,
                playlistName = playlistName,
                deviceId = deviceId,
                deviceKey = deviceKey,
                onLoaded = {
                    nav.navigate("home/$deviceId/$deviceKey/$playlistId") {
                        popUpTo("playlists/$deviceId/$deviceKey") { inclusive = false }
                    }
                },
                onError = { nav.popBackStack() }
            )
        }
        composable("home/{deviceId}/{deviceKey}/{playlistId}") { backStack ->
            val deviceId = backStack.arguments?.getString("deviceId") ?: ""
            val deviceKey = backStack.arguments?.getString("deviceKey") ?: ""
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            HomeScreen(
                playlistName = CatalogRepository.playlistName ?: LiveTvCache.playlistName ?: "Playlist",
                onNavigate = { route ->
                    when (route) {
                        "live" -> nav.navigate("live/$deviceId/$deviceKey/$playlistId")
                        "movies" -> nav.navigate("movies/$deviceId/$deviceKey/$playlistId")
                        "series" -> nav.navigate("series/$deviceId/$deviceKey/$playlistId")
                        else -> nav.navigate(route)
                    }
                }
            )
        }
        // TV ao Vivo / Filmes / Séries: mesma tela com abas
        for (tab in BrowseTab.entries) {
            composable("${tab.route}/{deviceId}/{deviceKey}/{playlistId}") { backStack ->
                val deviceId = backStack.arguments?.getString("deviceId") ?: ""
                val deviceKey = backStack.arguments?.getString("deviceKey") ?: ""
                val playlistId = backStack.arguments?.getString("playlistId") ?: ""
                BrowseScreen(
                    playlistId = playlistId,
                    deviceId = deviceId,
                    deviceKey = deviceKey,
                    initialTab = tab,
                    onPlay = { request ->
                        PlaybackSession.current = request
                        nav.navigate("player")
                    },
                    onBack = { nav.popBackStack() }
                )
            }
        }
        composable("settings") {
            SettingsScreen(
                deviceId = savedDeviceId,
                deviceKey = savedDeviceKey,
                onBack = { nav.popBackStack() }
            )
        }
        composable("player") {
            val request = PlaybackSession.current
            if (request == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                PlayerScreen(request = request, onBack = { nav.popBackStack() })
            }
        }
        composable("player/{url}/{title}") { backStack ->
            val encUrl = backStack.arguments?.getString("url") ?: ""
            val encTitle = backStack.arguments?.getString("title") ?: "Canal"
            val url = URLDecoder.decode(encUrl, StandardCharsets.UTF_8.toString())
            val title = URLDecoder.decode(encTitle, StandardCharsets.UTF_8.toString())
            PlayerScreen(streamUrl = url, title = title, onBack = { nav.popBackStack() })
        }
    }
}