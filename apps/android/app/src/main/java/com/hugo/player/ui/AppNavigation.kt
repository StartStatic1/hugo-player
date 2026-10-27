package com.hugo.player.ui

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.hugo.player.data.AppSettings
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
    UpdateGate { AppNavigationContent() }
}

@Composable
private fun AppNavigationContent() {
    val context = LocalContext.current
    val nav = rememberNavController()
    val identity = remember { DeviceIdentity.get(context) }
    val startDest = remember { if (DeviceIdentity.isActivationDone(context)) "playlists?open={open}&refresh={refresh}" else "activation" }
    // "Abrir última playlist" ligado: ao abrir o app, já entra na última playlist aberta (se ainda existir;
    // se foi apagada ou o app precisa atualizar, fica na lista normal). Só no arranque.
    val startOpenId = remember {
        if (DeviceIdentity.isActivationDone(context) && AppSettings.autoOpenLastPlaylist) AppSettings.lastPlaylistId?.takeIf { it.isNotBlank() } else null
    }

    NavHost(navController = nav, startDestination = startDest) {
        composable("activation") {
            ActivationScreen(onActivated = { _, _ ->
                nav.navigate("playlists?open=&refresh=") { popUpTo("activation") { inclusive = true } }
            })
        }
        composable(
            "playlists?open={open}&refresh={refresh}",
            arguments = listOf(
                navArgument("open") { type = NavType.StringType; nullable = true; defaultValue = startOpenId },
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
            val playlistId = LiveTvCache.playlistId ?: ""
            FavoritesScreen(
                playlistId = playlistId,
                // cada favorito abre na tela certa: canal -> player ao vivo, série -> episódios, filme -> detalhes
                onOpen = { e, liveFavs ->
                    val fav = e.fav
                    when (e.kind) {
                        "series" -> nav.navigate("seriesDetail/${enc(fav.playlistId)}/${enc(fav.title)}")
                        "live" -> {
                            val ch = e.channel ?: ChannelEntity(name = fav.title, url = fav.url, logo = fav.logo, group = "", type = "live", playlistId = fav.playlistId)
                            val list = liveFavs.ifEmpty { listOf(ch) }
                            val n = list.indexOfFirst { it.url == ch.url }.coerceAtLeast(0) + 1
                            LiveSession.current = LivePlayRequest(LiveListSource(fav.playlistId, LiveFilters.FAV, "", "Favoritos"), ch, n, fixedList = list)
                            nav.navigate("livePlay")
                        }
                        else -> nav.openMovie(e.channel ?: ChannelEntity(name = fav.title, url = fav.url, logo = fav.logo, group = "", type = "movie", playlistId = fav.playlistId))
                    }
                },
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

/** Favorito já com o tipo descoberto (canal / filme / série). */
private data class FavEntry(val fav: WatchHistory.FavoriteItem, val kind: String, val channel: ChannelEntity?)

@Composable
private fun FavoritesScreen(playlistId: String, onOpen: (FavEntry, List<ChannelEntity>) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { com.hugo.player.data.CatalogRepository.get(context) }
    var tick by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<FavEntry>?>(null) }
    var otherCount by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<FavEntry?>(null) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(playlistId, tick) {
        val all = WatchHistory.listFavorites(context)
        val mine = all.filter { it.playlistId == playlistId }
        otherCount = all.size - mine.size
        val byUrl = repo.byUrls(playlistId, mine.filter { !it.key.startsWith("series:") && it.url.isNotBlank() }.map { it.url }).associateBy { it.url }
        entries = mine.map { f ->
            when {
                f.key.startsWith("series:") -> FavEntry(f, "series", null)
                else -> byUrl[f.url]?.let { FavEntry(f, it.type, it) } ?: FavEntry(f, "movie", null)
            }
        }
    }
    LaunchedEffect(entries != null) { if (!entries.isNullOrEmpty()) { kotlinx.coroutines.delay(80); runCatching { firstFocus.requestFocus() } } }

    menuFor?.let { e ->
        FavoriteMenu(
            title = e.fav.title,
            onOpen = { menuFor = null; onOpen(e, entries.orEmpty().filter { it.kind == "live" }.mapNotNull { it.channel }) },
            onRemove = { WatchHistory.removeFavorite(context, e.fav.key); menuFor = null; tick++ },
            onDismiss = { menuFor = null },
        )
    }

    Column(Modifier.fillMaxSize().background(HugoColors.Background)) {
        Row(Modifier.fillMaxWidth().background(HugoColors.Panel).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text("Favoritos", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("OK abre · segure OK ou ≡ para remover", color = HugoColors.TextDim, fontSize = 11.sp)
        }
        val list = entries
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = HugoColors.Accent) }
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nenhum favorito nesta playlist.\nFavorite filmes, séries ou canais." + if (otherCount > 0) "\n($otherCount em outras playlists)" else "",
                    color = HugoColors.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                var firstAssigned = false
                listOf("live" to "Canais", "movie" to "Filmes", "series" to "Séries").forEach { (kind, label) ->
                    val group = list.filter { it.kind == kind }
                    if (group.isEmpty()) return@forEach
                    item(key = "h:$kind", span = { GridItemSpan(maxLineSpan) }) {
                        Text("$label (${group.size})", color = HugoColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    group.forEachIndexed { i, e ->
                        val isFirst = !firstAssigned && i == 0
                        if (isFirst) firstAssigned = true
                        item(key = e.fav.key) {
                            var longPressed by remember { mutableStateOf(false) }
                            FocusSurface(
                                onClick = { onOpen(e, list.filter { it.kind == "live" }.mapNotNull { it.channel }) },
                                onLongClick = { menuFor = e },
                                modifier = Modifier.fillMaxWidth()
                                    .then(if (isFirst) Modifier.focusRequester(firstFocus) else Modifier)
                                    // controle: ≡ (MENU) ou segurar OK abre o menu (remover)
                                    .onPreviewKeyEvent { ev ->
                                        val code = ev.key.nativeKeyCode
                                        val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                                        when {
                                            ev.type == KeyEventType.KeyDown && code == android.view.KeyEvent.KEYCODE_MENU -> { menuFor = e; true }
                                            ev.type == KeyEventType.KeyDown && ok && ev.nativeKeyEvent.repeatCount > 0 -> { if (!longPressed) { longPressed = true; menuFor = e }; true }
                                            ev.type == KeyEventType.KeyUp && ok && longPressed -> { longPressed = false; true }
                                            else -> false
                                        }
                                    },
                                color = Color.Transparent,
                                focusedColor = HugoColors.Accent.copy(alpha = 0.35f),
                                focusScale = 1.05f,
                                focusBorderWidth = 3.dp,
                            ) { _ ->
                                Column(Modifier.fillMaxWidth().padding(3.dp)) {
                                    Box(
                                        Modifier.fillMaxWidth().aspectRatio(if (kind == "live") 1f else 2f / 3f).clip(RoundedCornerShape(10.dp)).background(HugoColors.Card),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        e.fav.logo?.let { SubcomposeAsyncImage(model = it, contentDescription = e.fav.title, contentScale = if (kind == "live") ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (kind == "live") 12.dp else 0.dp)) }
                                            ?: Text(e.fav.title.take(2).uppercase(), color = HugoColors.TextMuted, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Text(e.fav.title, color = Color.White, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp))
                                }
                            }
                        }
                    }
                }
                if (otherCount > 0) item(key = "others", span = { GridItemSpan(maxLineSpan) }) {
                    Text("+ $otherCount favorito(s) em outras playlists — abra a playlist para vê-los.", color = HugoColors.TextDim, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun FavoriteMenu(title: String, onOpen: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).background(HugoColors.Panel, RoundedCornerShape(16.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            listOf("Abrir" to onOpen, "Remover dos favoritos" to onRemove, "Cancelar" to onDismiss).forEachIndexed { i, (label, action) ->
                FocusSurface(
                    onClick = action,
                    modifier = Modifier.fillMaxWidth().height(46.dp).padding(vertical = 3.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    color = if (i == 1) Color(0x55EC4899) else HugoColors.CardAlt,
                    focusedColor = if (i == 1) Color(0xFFEC4899) else HugoColors.Accent,
                    focusBorderWidth = 3.dp,
                    focusScale = 1.02f,
                    shape = RoundedCornerShape(10.dp),
                ) { _ -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) } }
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
}
