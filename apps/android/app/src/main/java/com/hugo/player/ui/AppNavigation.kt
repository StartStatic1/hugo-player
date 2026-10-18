package com.hugo.player.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hugo.player.data.DeviceIdentity
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.player.PlaybackSession
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.toString())
private fun dec(s: String) = URLDecoder.decode(s, StandardCharsets.UTF_8.toString())

/** Volta para a tela de Playlists (limpando a pilha), opcionalmente com ação automática. */
fun NavHostController.goToPlaylists(query: String = "open=&refresh=") {
    navigate("playlists?$query") {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = false
    }
}

fun NavHostController.openPlayer(url: String, title: String) = navigate("player/${enc(url)}/${enc(title)}")

/** Abre o player com um PlaybackRequest completo (fila de episódios, ao vivo/VOD). */
fun NavHostController.openPlayer(request: PlaybackRequest) {
    PlaybackSession.current = request
    navigate("play")
}

/**
 * Fluxo: Ativação (ID/Key gerados) → Playlists (carrega dentro do card) → Home → abas.
 * Device ID/Key vêm de DeviceIdentity (não vão mais na rota).
 */
@Composable
fun AppNavigation() {
    UpdateCheckerHost()
    val context = LocalContext.current
    val nav = rememberNavController()
    val identity = remember { DeviceIdentity.get(context) }
    val startDest = remember { if (DeviceIdentity.isActivationDone(context)) "playlists?open={open}&refresh={refresh}" else "activation" }

    NavHost(navController = nav, startDestination = startDest) {
        composable("activation") {
            ActivationScreen(onActivated = { _, _ ->
                nav.navigate("playlists?open=&refresh=") { popUpTo("activation") { inclusive = true } }
            })
        }
        composable(
            "playlists?open={open}&refresh={refresh}",
            arguments = listOf(
                navArgument("open") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("refresh") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStack ->
            PlaylistScreen(
                onOpen = { _, _ ->
                    nav.navigate("home") {
                        popUpTo("playlists?open={open}&refresh={refresh}") { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onSettings = { nav.navigate("settings") },
                autoOpenId = backStack.arguments?.getString("open")?.takeIf { it.isNotBlank() && it != "null" },
                autoRefresh = backStack.arguments?.getString("refresh") == "1",
            )
        }
        composable("home") {
            val playlistId = LiveTvCache.playlistId ?: ""
            HomeScreen(
                playlistId = playlistId,
                playlistName = LiveTvCache.playlistName ?: "Playlist",
                onNavigate = { route ->
                    when {
                        route == "live" -> nav.navigate("live/$playlistId")
                        route == "movies" -> nav.navigate("movies/$playlistId")
                        route == "series" -> nav.navigate("series/$playlistId")
                        route == "playlists" -> nav.goToPlaylists()
                        route == "refresh" -> nav.goToPlaylists("refresh=1&open=${enc(playlistId)}")
                        route.startsWith("recent:") -> nav.goToPlaylists("open=${enc(route.removePrefix("recent:"))}&refresh=")
                        else -> nav.navigate(route)
                    }
                }
            )
        }
        composable("live/{playlistId}") { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            BrowseScreen(playlistId, BrowseType.LIVE, onPlay = { url, title -> nav.openPlayer(PlaybackRequest(url = url, title = title, subtitle = "TV ao Vivo", isLive = true, playlistId = playlistId)) }, onOpenSeries = {}, onBack = { nav.popBackStack() })
        }
        composable("movies/{playlistId}") { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            BrowseScreen(playlistId, BrowseType.MOVIES, onPlay = { url, title -> nav.openPlayer(PlaybackRequest(url = url, title = title, subtitle = "Filme", isLive = false, playlistId = playlistId)) }, onOpenSeries = {}, onBack = { nav.popBackStack() })
        }
        composable("series/{playlistId}") { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            BrowseScreen(
                playlistId, BrowseType.SERIES,
                onPlay = { url, title -> nav.openPlayer(url, title) },
                onOpenSeries = { name -> nav.navigate("seriesDetail/${enc(playlistId)}/${enc(name)}") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("seriesDetail/{playlistId}/{name}") { backStack ->
            val playlistId = dec(backStack.arguments?.getString("playlistId") ?: "")
            val name = dec(backStack.arguments?.getString("name") ?: "")
            SeriesDetailScreen(playlistId, name, onPlay = { req -> nav.openPlayer(req.copy(playlistId = playlistId)) }, onBack = { nav.popBackStack() })
        }
        composable("settings") { SettingsScreen(identity.deviceId, identity.deviceKey, onBack = { nav.popBackStack() }) }
        composable("play") {
            val req = remember { PlaybackSession.current }
            if (req != null) PlayerScreen(req, onBack = { nav.popBackStack() })
            else LaunchedEffect(Unit) { nav.popBackStack() }
        }
        composable("player/{url}/{title}") { backStack ->
            val url = dec(backStack.arguments?.getString("url") ?: "")
            val title = dec(backStack.arguments?.getString("title") ?: "")
            PlayerScreen(PlaybackRequest(url = url, title = title), onBack = { nav.popBackStack() })
        }
    }
}
