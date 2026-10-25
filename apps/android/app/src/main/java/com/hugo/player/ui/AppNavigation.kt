package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.SubcomposeAsyncImage
import com.hugo.player.data.DeviceIdentity
import com.hugo.player.data.WatchHistory
import com.hugo.player.data.db.ChannelEntity
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

fun NavHostController.goToPlaylists(query: String = "open=&refresh=") {
    navigate("playlists?$query") { popUpTo(graph.id) { inclusive = true }; launchSingleTop = false }
}

fun NavHostController.openPlayer(url: String, title: String) = navigate("player/${enc(url)}/${enc(title)}")

fun NavHostController.openPlayer(request: PlaybackRequest) {
    PlaybackSession.current = request
    navigate("play")
}

fun NavHostController.openMovie(movie: ChannelEntity) {
    MovieSession.current = movie
    navigate("movieDetail")
}

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
                onOpen = { _, _ -> nav.navigate("home") { popUpTo("playlists?open={open}&refresh={refresh}") { inclusive = false }; launchSingleTop = true } },
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
                            if (item != null) nav.openPlayer(PlaybackRequest(url = item.url, title = item.title, subtitle = item.subtitle, isLive = false, playlistId = item.playlistId, logo = item.logo))
                        }
                        route.startsWith("recent:") -> nav.goToPlaylists("open=${enc(route.removePrefix("recent:"))}&refresh=")
                        else -> nav.navigate(route)
                    }
                }
            )
        }
        composable("live/{playlistId}") { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            // TV ao vivo sempre no formato novo (o layout se ajusta à largura: TV, tablet ou celular deitado)
            LiveTvScreen(playlistId, onPlay = { req -> LiveSession.current = req; nav.navigate("livePlay") }, onBack = { nav.popBackStack() })
        }
        composable("movies/{playlistId}") { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            BrowseScreen(playlistId, BrowseType.MOVIES, onPlay = { _, _ -> }, onOpenSeries = {}, onOpenMovie = { m -> nav.openMovie(m) }, onBack = { nav.popBackStack() })
        }
        composable("series/{playlistId}") { backStack ->
            val playlistId = backStack.arguments?.getString("playlistId") ?: ""
            BrowseScreen(playlistId, BrowseType.SERIES, onPlay = { url, title -> nav.openPlayer(url, title) }, onOpenSeries = { name -> nav.navigate("seriesDetail/${enc(playlistId)}/${enc(name)}") }, onOpenMovie = {}, onBack = { nav.popBackStack() })
        }
        composable("seriesDetail/{playlistId}/{name}") { backStack ->
            val playlistId = dec(backStack.arguments?.getString("playlistId") ?: "")
            val name = dec(backStack.arguments?.getString("name") ?: "")
            SeriesDetailScreen(playlistId, name, onPlay = { req -> nav.openPlayer(req.copy(playlistId = playlistId)) }, onBack = { nav.popBackStack() })
        }
        composable("movieDetail") {
            val movie = remember { MovieSession.current }
            if (movie != null) {
                MovieDetailScreen(movie = movie, onPlay = { req -> nav.openPlayer(req) }, onOpenSimilar = { m -> MovieSession.current = m; nav.navigate("movieDetail") }, onBack = { nav.popBackStack() })
            } else LaunchedEffect(Unit) { nav.popBackStack() }
        }
        composable("settings") { SettingsScreen(identity.deviceId, identity.deviceKey, onBack = { nav.popBackStack() }) }
        composable("favorites") {
            FavoritesScreen(
                onOpen = { fav -> nav.openMovie(ChannelEntity(name = fav.title, url = fav.url, logo = fav.logo, group = "", type = "movie", playlistId = fav.playlistId)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("livePlay") {
            val req = remember { LiveSession.current }
            if (req != null) LivePlayerScreen(req, onBack = { nav.popBackStack() })
            else LaunchedEffect(Unit) { nav.popBackStack() }
        }
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
private fun FavoritesScreen(onOpen: (WatchHistory.FavoriteItem) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(WatchHistory.listFavorites(context)) }

    Column(Modifier.fillMaxSize().background(HugoColors.Background)) {
        Row(Modifier.fillMaxWidth().background(HugoColors.Panel).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("Favoritos", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum favorito ainda.\nFavorite filmes, séries ou canais.", color = HugoColors.TextMuted, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = { it.key }) { fav ->
                    Box {
                        // cartão focável com borda visível no controle remoto
                        FocusSurface(
                            onClick = { onOpen(fav) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Transparent,
                            focusedColor = HugoColors.Accent.copy(alpha = 0.35f),
                            focusScale = 1.05f,
                            focusBorderWidth = 3.dp,
                        ) { _ ->
                            Column(Modifier.fillMaxWidth().padding(3.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(HugoColors.Card)) {
                                    fav.logo?.let { SubcomposeAsyncImage(model = it, contentDescription = fav.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                }
                                Text(fav.title, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp))
                            }
                        }
                        IconButton(
                            onClick = { WatchHistory.removeFavorite(context, fav.key); items = WatchHistory.listFavorites(context) },
                            modifier = Modifier.align(Alignment.TopEnd).size(28.dp).offset(x = 4.dp, y = (-4).dp),
                        ) {
                            Icon(Icons.Default.Close, "Remover", tint = Color.White, modifier = Modifier.size(16.dp).background(Color(0xCC000000), RoundedCornerShape(50)))
                        }
                    }
                }
            }
        }
    }
}
