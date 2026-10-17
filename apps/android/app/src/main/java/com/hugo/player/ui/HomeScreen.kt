package com.hugo.player.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.RecentPlaylists
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoLogo
import com.hugo.player.ui.components.InitialAvatar
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LiveBrush = Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFA855F7)))
private val MoviesBrush = Brush.linearGradient(listOf(Color(0xFFDB2777), Color(0xFFF97316)))
private val SeriesBrush = Brush.linearGradient(listOf(Color(0xFF0891B2), Color(0xFF22C55E)))

/**
 * Home estilo IBO: logo no topo, atalhos TV ao Vivo / Filmes / Séries com contagem,
 * cards menores de utilidades e playlists recentes. Tudo navegável por D-pad.
 * Rotas emitidas: live, movies, series, playlists, settings, refresh, recent:<id>.
 */
@Composable
fun HomeScreen(playlistId: String, playlistName: String, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository.get(context) }
    var counts by remember { mutableStateOf(Triple(-1, -1, -1)) }
    val recents = remember { RecentPlaylists.list(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val liveFocus = remember { FocusRequester() }

    LaunchedEffect(playlistId) {
        counts = Triple(repo.count(playlistId, "live"), repo.count(playlistId, "movie"), repo.count(playlistId, "series"))
    }
    LaunchedEffect(Unit) {
        runCatching { liveFocus.requestFocus() }
        while (true) { delay(30_000); now = System.currentTimeMillis() }
    }
    val nf = remember { NumberFormat.getIntegerInstance(Locale("pt", "BR")) }
    fun countLabel(n: Int, unit: String) = when { n < 0 -> " "; n == 0 -> "Vazio"; else -> "${nf.format(n)} $unit" }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF110E24), HugoColors.Background)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        // Topo: logo, playlist atual, relógio
        Row(verticalAlignment = Alignment.CenterVertically) {
            HugoLogo(markSize = 40.dp, textSize = 24.sp)
            Spacer(Modifier.width(18.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = HugoColors.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(playlistName, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(now)), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("EEE, d 'de' MMM", Locale("pt", "BR")).format(Date(now)), color = HugoColors.TextMuted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(26.dp))

        // Atalhos principais
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            MainTile("TV ao Vivo", countLabel(counts.first, "canais"), Icons.Default.LiveTv, LiveBrush, Modifier.weight(1f).focusRequester(liveFocus)) { onNavigate("live") }
            MainTile("Filmes", countLabel(counts.second, "filmes"), Icons.Default.Movie, MoviesBrush, Modifier.weight(1f)) { onNavigate("movies") }
            MainTile("Séries", countLabel(counts.third, "episódios"), Icons.Default.VideoLibrary, SeriesBrush, Modifier.weight(1f)) { onNavigate("series") }
        }

        Spacer(Modifier.height(16.dp))

        // Cards pequenos de utilidades
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SmallTile("Playlists", Icons.AutoMirrored.Filled.PlaylistPlay, Color(0xFF8B5CF6), Modifier.weight(1f)) { onNavigate("playlists") }
            SmallTile("Atualizar conteúdo", Icons.Default.Sync, Color(0xFF06B6D4), Modifier.weight(1f)) { onNavigate("refresh") }
            SmallTile("Configurações", Icons.Default.Settings, Color(0xFF64748B), Modifier.weight(1f)) { onNavigate("settings") }
        }

        Spacer(Modifier.height(26.dp))

        // Playlists recentes
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = HugoColors.TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Playlists recentes", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        if (recents.isEmpty()) {
            Text("As playlists que você abrir aparecem aqui.", color = HugoColors.TextMuted, fontSize = 13.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
                items(recents, key = { it.id }) { r ->
                    val current = r.id == playlistId
                    FocusSurface(
                        onClick = { if (current) Unit else onNavigate("recent:${r.id}") },
                        modifier = Modifier.width(230.dp).height(72.dp),
                        shape = RoundedCornerShape(14.dp),
                        selected = current,
                        selectedColor = Color(0xFF2A1F4D),
                    ) { _ ->
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            InitialAvatar(r.name, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (current) "Em uso agora" else DateUtils.getRelativeTimeSpanString(r.openedAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                                    color = if (current) HugoColors.Accent else HugoColors.TextMuted, fontSize = 11.sp, maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainTile(title: String, subtitle: String, icon: ImageVector, brush: Brush, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.height(150.dp), shape = RoundedCornerShape(18.dp), focusScale = 1.05f) { focused ->
        Box(Modifier.fillMaxSize().background(brush)) {
            Icon(icon, null, tint = Color.White.copy(alpha = 0.18f), modifier = Modifier.size(110.dp).align(Alignment.BottomEnd).offset(x = 18.dp, y = 18.dp))
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Column {
                    Text(title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
            }
            if (focused) Box(Modifier.fillMaxSize().border(3.dp, Color.White, RoundedCornerShape(18.dp)))
        }
    }
}

@Composable
private fun SmallTile(title: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.height(58.dp), shape = RoundedCornerShape(14.dp), focusedColor = color.copy(alpha = 0.35f)) { _ ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
