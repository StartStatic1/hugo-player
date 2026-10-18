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
            val openId = backStack.arguments?.getString("open")?.takeIf { it.isNotBlank() }
            val doRefresh = backStack.arguments?.getString("refresh") == "1"
            PlaylistScreen(
                onOpenPlaylist = { id, name ->
                    nav.navigate("home/${enc(id)}/${enc(name)}")
                },
                onOpenSettings = { nav.navigate("settings") },
                autoOpenId = openId,
                forceRefresh = doRefresh,
            )
        }
        composable("home/{playlistId}/{playlistName}") { backStack ->
            val playlistId = dec(backStack.arguments?.getString("playlistId") ?: "")
            val playlistName = dec(backStack.arguments?.getString("playlistName") ?: "")
            HomeScreen(
                playlistId = playlistId,
                playlistName = playlistName,
                onNavigate = { route ->
                    when {
                        route == "playlists" -> nav.goToPlaylists()
                        route == "settings" -> nav.navigate("settings")
                        route == "live" -> nav.navigate("live/${enc(playlistId)}")
                        route == "movies" -> nav.navigate("movies/${enc(playlistId)}")
                        route == "series" -> nav.navigate("series/${enc(playlistId)}")
                        route == "refresh" -> nav.goToPlaylists("open=${enc(playlistId)}&refresh=1")
                        route.startsWith("recent:") -> {
                            val id = route.removePrefix("recent:")
                            nav.navigate("home/${enc(id)}/${enc(id)}")
                        }
                    }
                },
            )
        }
        composable("live/{playlistId}") { backStack ->
            val playlistId = dec(backStack.arguments?.getString("playlistId") ?: "")
            BrowseScreen(playlistId, BrowseType.LIVE, onPlay = { url, title -> nav.openPlayer(PlaybackRequest(url = url, title = title, subtitle = "Ao vivo", isLive = true)) }, onOpenSeries = {}, onBack = { nav.popBackStack() })
        }
        composable("movies/{playlistId}") { backStack ->
            val playlistId = dec(backStack.arguments?.getString("playlistId") ?: "")
            BrowseScreen(playlistId, BrowseType.MOVIES, onPlay = { url, title -> nav.openPlayer(PlaybackRequest(url = url, title = title, subtitle = "Filme", isLive = false)) }, onOpenSeries = {}, onBack = { nav.popBackStack() })
        }
        composable("series/{playlistId}") { backStack ->
            val playlistId = dec(backStack.arguments?.getString("playlistId") ?: "")
            BrowseScreen(playlistId, BrowseType.SERIES, onPlay = { _, _ -> }, onOpenSeries = { seriesId, name, logo ->
                nav.navigate("series-detail/${enc(playlistId)}/${enc(seriesId)}/${enc(name)}/${enc(logo ?: "")}")
            }, onBack = { nav.popBackStack() })
        }
        composable("series-detail/{playlistId}/{seriesId}/{name}/{logo}") { backStack ->
            val playlistId = dec(backStack.arguments?.getString("playlistId") ?: "")
            val seriesId = dec(backStack.arguments?.getString("seriesId") ?: "")
            val name = dec(backStack.arguments?.getString("name") ?: "")
            val logo = dec(backStack.arguments?.getString("logo") ?: "").ifBlank { null }
            SeriesDetailScreen(
                playlistId = playlistId,
                seriesId = seriesId,
                seriesName = name,
                seriesLogo = logo,
                onPlay = { req -> nav.openPlayer(req) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable("play") {
            PlayerScreen(onBack = { nav.popBackStack() })
        }
        composable("player/{url}/{title}") { backStack ->
            val url = dec(backStack.arguments?.getString("url") ?: "")
            val title = dec(backStack.arguments?.getString("title") ?: "")
            PlaybackSession.current = PlaybackRequest(url = url, title = title)
            PlayerScreen(onBack = { nav.popBackStack() })
        }
    }
}
