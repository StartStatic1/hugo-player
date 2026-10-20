package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hugo.player.data.DeviceIdentity
import com.hugo.player.data.WatchHistory
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.player.PlaybackSession
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoIconButton
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
                        route == "favorites" -> nav.navigate("favorites")
                        route.startsWith("continue:") -> {
                            val key = route.removePrefix("continue:")
                            val item = WatchHistory.listContinue(context).find { it.key == key }
                            if (item != null) {
                                nav.openPlayer(PlaybackRequest(url = item.url, title = item.title, playlistId = item.playlistId, logo = item.logo))
                            }
                        }
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
        composable("favorites") { FavoritesScreen(onBack = { nav.popBackStack() }) }
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

@Composable
private fun FavoritesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keys = remember { WatchHistory.favoriteKeys(context) }

    Column(Modifier.fillMaxSize().background(HugoColors.Background)) {
        Row(
            Modifier.fillMaxWidth().background(HugoColors.Panel).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("Favoritos", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        if (keys.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum favorito ainda.\nFavorite filmes e séries para aparecerem aqui.", color = HugoColors.TextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(keys.toList()) { key ->
                    FocusSurface(
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(key, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
