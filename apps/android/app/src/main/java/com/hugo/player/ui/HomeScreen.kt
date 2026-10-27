package com.hugo.player.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.PlaylistRepository
import com.hugo.player.data.SeriesEpisodes
import com.hugo.player.data.WatchHistory
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 3 cores bem diferentes: vermelho (ao vivo), violeta (filmes), verde-água (séries)
private val LiveBrush = Brush.linearGradient(listOf(Color(0xFFE11D48), Color(0xFFF97316)))
private val MoviesBrush = Brush.linearGradient(listOf(Color(0xFF6D28D9), Color(0xFF4F46E5)))
private val SeriesBrush = Brush.linearGradient(listOf(Color(0xFF0E7490), Color(0xFF059669)))

private val ShortcutPlaylists = Color(0xFFA78BFA)
private val ShortcutFavorites = Color(0xFFF472B6)
private val ShortcutRefresh = Color(0xFF2DD4BF)
private val ShortcutSettings = Color(0xFF94A3B8)

/**
 * Home: topo (logo, playlist em uso = botão "Trocar", relógio), 3 cartões grandes
 * TV ao Vivo / Filmes / Séries, "Continuar assistindo" (uma série = um cartão; segurar OK remove)
 * Favoritos/Playlists (cartões médios), atalhos no topo (Atualizar, Playlist, Ajustes) e rodapé com o vencimento.
 * D-pad: topo → cartões grandes → Favoritos/Playlists → Continuar assistindo; foco inicial em "TV ao Vivo".
 * Rotas emitidas: live, movies, series, playlists, settings, refresh, favorites.
 */
@Composable
fun HomeScreen(playlistId: String, playlistName: String, onNavigate: (String) -> Unit, onPlay: (PlaybackRequest) -> Unit) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository.get(context) }
    val scope = rememberCoroutineScope()
    var counts by remember { mutableStateOf(Triple(-1, -1, -1)) }
    fun loadContinue() = WatchHistory.groupContinue(WatchHistory.listContinue(context, playlistId))
    var continueItems by remember { mutableStateOf(loadContinue()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val syncedAt = remember(playlistId) { PlaylistRepository.syncedAt(context, playlistId) }
    val expiry = remember(playlistId) { com.hugo.player.data.ListExpiry.get(context, playlistId) }
    val favCount = remember(playlistId) { runCatching { WatchHistory.listFavorites(context).count { it.playlistId == playlistId } }.getOrDefault(0) }
    val liveFocus = remember { FocusRequester() }
    var menuFor by remember { mutableStateOf<WatchHistory.ProgressItem?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId) {
        counts = Triple(repo.count(playlistId, "live"), repo.mergedMovieCount(playlistId), repo.seriesCount(playlistId, null, null))
        continueItems = loadContinue()
    }
    LaunchedEffect(Unit) {
        runCatching { liveFocus.requestFocus() }
        while (true) { delay(30_000); now = System.currentTimeMillis() }
    }
    val nf = remember { NumberFormat.getIntegerInstance(Locale("pt", "BR")) }
    // playlist toda vazia (download falhou / nunca atualizou): os cartões levam para "Atualizar"
    val allEmpty = counts.first == 0 && counts.second == 0 && counts.third == 0
    fun countLabel(n: Int, unit: String) = when {
        n < 0 -> " "
        allEmpty -> "Vazio · toque para atualizar"
        n == 0 -> "Vazio"
        else -> "${nf.format(n)} $unit"
    }
    fun tileClick(route: String) = onNavigate(if (allEmpty) "refresh" else route)

    fun openContinue(item: WatchHistory.ProgressItem) {
        if (opening != null) return
        val base = PlaybackRequest(url = item.url, title = item.title, subtitle = item.subtitle, isLive = false, playlistId = item.playlistId, logo = item.logo)
        if (!WatchHistory.isEpisode(item)) { onPlay(base); return }
        // episódio: monta a fila dos próximos para o "próximo episódio automático" (sem rede/demora: abre só ele)
        opening = item.key
        scope.launch {
            val job = CoroutineScope(Dispatchers.IO).async {
                runCatching {
                    val loaded = SeriesEpisodes.load(context, item.playlistId, item.title)
                    SeriesEpisodes.queueAfter(loaded.episodes, item.url, item.subtitle) { ep ->
                        PlaybackRequest(url = ep.url, title = item.title, subtitle = epLabel(ep), isLive = false, playlistId = item.playlistId, logo = item.logo)
                    }
                }.getOrNull()
            }
            val queue = withTimeoutOrNull(8_000) { job.await() }.orEmpty()
            opening = null
            onPlay(base.copy(queue = queue))
        }
    }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF110E24), HugoColors.Background)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HugoLogo(markSize = 40.dp, textSize = 24.sp)
            Spacer(Modifier.width(18.dp))
            // playlist em uso: clicar troca de playlist
            FocusSurface(
                onClick = { onNavigate("playlists") },
                shape = RoundedCornerShape(50),
                color = Color(0x1FFFFFFF),
                focusedColor = ShortcutPlaylists.copy(alpha = 0.30f),
                focusBorderColor = ShortcutPlaylists,
                focusScale = 1.03f,
            ) { focused ->
                Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, tint = ShortcutPlaylists, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(playlistName, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.SwapHoriz, null, tint = if (focused) Color.White else HugoColors.TextMuted, modifier = Modifier.size(16.dp))
                    if (focused) {
                        Spacer(Modifier.width(4.dp))
                        Text("Trocar", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(now)), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("EEE, d 'de' MMM", Locale("pt", "BR")).format(Date(now)), color = HugoColors.TextMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.width(18.dp))
            // atalhos do topo (canto direito): ícone + rótulo curto
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TopAction("Atualizar", Icons.Rounded.Refresh, ShortcutRefresh) { onNavigate("refresh") }
                TopAction("Trocar", Icons.Rounded.SwapHoriz, ShortcutPlaylists) { onNavigate("playlists") }
                TopAction("Ajustes", Icons.Rounded.Settings, ShortcutSettings) { onNavigate("settings") }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            MainTile("TV ao Vivo", countLabel(counts.first, "canais"), Icons.Rounded.LiveTv, LiveBrush, Modifier.weight(1f).focusRequester(liveFocus)) { tileClick("live") }
            MainTile("Filmes", countLabel(counts.second, "filmes"), Icons.Rounded.Movie, MoviesBrush, Modifier.weight(1f)) { tileClick("movies") }
            MainTile("Séries", countLabel(counts.third, "séries"), Icons.Rounded.Subscriptions, SeriesBrush, Modifier.weight(1f)) { tileClick("series") }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            ShortcutChip("Favoritos", favCount.takeIf { it > 0 }?.let { "$it salvo(s) nesta playlist" } ?: "Canais, filmes e séries que você marcar", Icons.Rounded.Favorite, ShortcutFavorites, Modifier.weight(1f)) { onNavigate("favorites") }
            ShortcutChip("Playlists", "Trocar, adicionar ou editar listas", Icons.AutoMirrored.Rounded.FormatListBulleted, ShortcutPlaylists, Modifier.weight(1f)) { onNavigate("playlists") }
        }

        if (continueItems.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayArrow, null, tint = HugoColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Continuar assistindo", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Text("segure OK para remover", color = HugoColors.TextDim, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
                items(continueItems, key = { it.key }) { item ->
                    ContinueCard(item, loading = opening == item.key, onClick = { openContinue(item) }, onMenu = { menuFor = item })
                }
            }
        }

        // rodapé discreto: playlist em uso · vencimento (Xtream) · última atualização
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(playlistName, color = HugoColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
            if (expiry != null) {
                Text(
                    " · " + expiry.label.removePrefix("Lista "),
                    color = if (expiry.level == com.hugo.player.data.ListExpiry.Level.OK || expiry.level == com.hugo.player.data.ListExpiry.Level.UNLIMITED) HugoColors.TextDim else expiryColor(expiry.level),
                    fontSize = 12.sp, fontWeight = if (expiry.level == com.hugo.player.data.ListExpiry.Level.OK || expiry.level == com.hugo.player.data.ListExpiry.Level.UNLIMITED) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1,
                )
            }
            syncedAt?.let {
                Text(" · atualizada " + DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase(Locale("pt", "BR")), color = HugoColors.TextDim, fontSize = 12.sp, maxLines = 1)
            }
        }
    }

    menuFor?.let { item ->
        ContinueMenu(
            title = item.title,
            subtitle = item.subtitle?.takeIf { WatchHistory.isEpisode(item) },
            onContinue = { menuFor = null; openContinue(item) },
            onRemove = {
                WatchHistory.removeContinue(context, item)
                menuFor = null
                continueItems = loadContinue()
                runCatching { liveFocus.requestFocus() }
            },
            onDismiss = { menuFor = null },
        )
    }
}

/** "T2 · E05" do subtítulo (sem o nome do episódio), para caber no cartão. */
private fun episodeCode(subtitle: String?): String? =
    subtitle?.let { Regex("""^(T\d+ · E\d+|Temporada \d+)""").find(it)?.value }

@Composable
private fun ContinueCard(item: WatchHistory.ProgressItem, loading: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    var longPressed by remember { mutableStateOf(false) }
    val episode = WatchHistory.isEpisode(item)
    val leftMin = if (item.durationMs > 0) ((item.durationMs - item.positionMs) / 60_000).coerceAtLeast(1) else null
    FocusSurface(
        onClick = onClick,
        onLongClick = onMenu,
        modifier = Modifier.width(270.dp).height(92.dp)
            // controle: segurar OK ou ≡ (MENU) abre o menu (remover)
            .onPreviewKeyEvent { ev ->
                val code = ev.key.nativeKeyCode
                val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
                when {
                    ev.type == KeyEventType.KeyDown && code == android.view.KeyEvent.KEYCODE_MENU -> { onMenu(); true }
                    ev.type == KeyEventType.KeyDown && ok && ev.nativeKeyEvent.repeatCount > 0 -> { if (!longPressed) { longPressed = true; onMenu() }; true }
                    ev.type == KeyEventType.KeyUp && ok && longPressed -> { longPressed = false; true }
                    else -> false
                }
            },
        shape = RoundedCornerShape(14.dp),
        focusBorderWidth = 3.dp,
    ) { _ ->
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(62.dp).fillMaxHeight().background(Color(0xFF0F1220)), contentAlignment = Alignment.Center) {
                if (!item.logo.isNullOrBlank()) coil.compose.SubcomposeAsyncImage(model = item.logo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (loading) {
                    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val line = when {
                        loading -> "Abrindo…"
                        episode -> listOfNotNull(episodeCode(item.subtitle), leftMin?.let { "faltam $it min" }).joinToString(" · ")
                        else -> leftMin?.let { "faltam $it min" }
                    }
                    if (!line.isNullOrBlank()) Text(line, color = if (episode && !loading) HugoColors.Accent else HugoColors.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(item.fraction).fillMaxHeight().background(HugoColors.Accent))
                }
            }
        }
    }
}

@Composable
private fun ContinueMenu(title: String, subtitle: String?, onContinue: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(320.dp).background(HugoColors.Panel, RoundedCornerShape(16.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            subtitle?.let { Text(episodeCode(it) ?: it, color = HugoColors.TextMuted, fontSize = 12.sp, maxLines = 1) }
            Spacer(Modifier.height(14.dp))
            listOf("Continuar" to onContinue, "Remover de Continuar assistindo" to onRemove, "Cancelar" to onDismiss).forEachIndexed { i, (label, action) ->
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
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
}

@Composable
private fun MainTile(title: String, subtitle: String, icon: ImageVector, brush: Brush, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.height(150.dp), shape = RoundedCornerShape(18.dp), focusScale = 1.05f) { focused ->
        Box(Modifier.fillMaxSize().background(brush)) {
            Icon(icon, null, tint = Color.White.copy(alpha = 0.16f), modifier = Modifier.size(112.dp).align(Alignment.BottomEnd).offset(x = 18.dp, y = 18.dp))
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Column {
                    Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (focused) Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(18.dp)))
        }
    }
}

/** Botão do topo: ícone num quadrado arredondado + rótulo curto; foco = fundo na cor do atalho e borda. */
@Composable
private fun TopAction(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(72.dp).height(62.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0x12FFFFFF),
        focusedColor = tint.copy(alpha = 0.22f),
        focusBorderWidth = 2.dp,
        focusBorderColor = tint,
        focusScale = 1.05f,
    ) { focused ->
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = label, tint = if (focused) Color.White else tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, color = if (focused) Color.White else HugoColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Atalho: cartão arredondado com ícone num círculo colorido, nome e uma linha de apoio. Foco = realce na cor do atalho. */
@Composable
private fun ShortcutChip(title: String, caption: String?, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(78.dp),
        shape = shape,
        color = Color(0x0FFFFFFF),
        focusedColor = tint.copy(alpha = 0.18f),
        focusBorderWidth = 2.dp,
        focusBorderColor = tint,
        focusScale = 1.03f,
    ) { focused ->
        Row(
            Modifier.fillMaxSize()
                .then(if (focused) Modifier else Modifier.border(1.dp, Color(0x1AFFFFFF), shape))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (focused) tint else tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (focused) Color(0xFF0B0D16) else tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (caption != null) Text(caption, color = if (focused) Color.White.copy(alpha = 0.8f) else HugoColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
