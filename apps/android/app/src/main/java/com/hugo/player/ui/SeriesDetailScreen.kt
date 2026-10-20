package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.hugo.player.data.AppSettings
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.TmdbClient
import com.hugo.player.data.WatchHistory
import org.json.JSONObject
import java.net.URL
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.player.PlaybackRequest
import com.hugo.player.ui.components.FocusSurface
import com.hugo.player.ui.components.HugoColors
import com.hugo.player.ui.components.HugoIconButton

private val SeriesAccent = Color(0xFF06B6D4)

internal fun episodeTitle(ep: ChannelEntity): String {
    var t = ep.name
    ep.seriesName?.let { if (t.startsWith(it, ignoreCase = true)) t = t.substring(it.length) }
    t = t.replace(Regex("^[\\s._-]*([Ss]\\d{1,2}[\\s._-]*[Ee]\\d{1,3}|\\d{1,2}x\\d{1,3}|[Tt]emp(?:orada)?\\.?\\s*\\d+\\s*[Ee]p(?:is[oó]dio)?\\.?\\s*\\d+)"), "")
    t = t.trim().trimStart('-', '|', ':', '.', '_').trim()
    return t
}

@Composable
fun SeriesDetailScreen(
    playlistId: String,
    seriesName: String,
    onPlay: (PlaybackRequest) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository.get(context) }
    var episodes by remember { mutableStateOf<List<ChannelEntity>?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedSeason by rememberSaveable { mutableStateOf<Int?>(null) }
    var info by remember { mutableStateOf<TmdbClient.Info?>(null) }
    val favKey = remember(seriesName) { "series:$playlistId:$seriesName" }
    var isFav by remember { mutableStateOf(WatchHistory.isFavorite(context, favKey)) }
    val firstEpisodeFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(playlistId, seriesName) {
        info = TmdbClient.searchTv(seriesName)
        val local = withContext(Dispatchers.IO) { repo.episodes(playlistId, seriesName) }
        val marker = local.firstOrNull()?.url
        if (marker != null && marker.startsWith("xtream://series/")) {
            val seriesId = marker.removePrefix("xtream://series/")
            var plUrl = LiveTvCache.playlistUrl
            if (plUrl.isNullOrBlank()) plUrl = AppSettings.lastPlaylistUrl
            if (plUrl.isNullOrBlank()) plUrl = ""
            val encoded = java.net.URLEncoder.encode(plUrl, "UTF-8")
            val endpoint = "${com.hugo.player.data.HugoApi.baseUrl}/channels/series-episodes?url=$encoded&seriesId=$seriesId"
            try {
                val body = withContext(Dispatchers.IO) {
                    val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 20000
                    conn.readTimeout = 30000
                    val code = conn.responseCode
                    val b = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) throw Exception("HTTP $code: ${b.take(200)}")
                    b
                }
                val json = JSONObject(body)
                val arr = json.optJSONArray("episodes")
                val list = mutableListOf<ChannelEntity>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        list.add(ChannelEntity(name = e.optString("name", seriesName), url = e.getString("url"), logo = e.optString("logo").takeIf { it.isNotEmpty() }, group = "", type = "series", playlistId = playlistId, seriesName = seriesName, season = e.optInt("season", 1), episode = e.optInt("episode", i + 1)))
                    }
                }
                episodes = list
            } catch (ex: Exception) {
                errorMsg = "Erro: ${ex.javaClass.simpleName}: ${ex.message}"
                episodes = local
            }
        } else episodes = local
    }
    val bySeason = remember(episodes) { episodes.orEmpty().groupBy { it.season ?: 1 }.toSortedMap() }
    LaunchedEffect(bySeason) { if (selectedSeason == null || selectedSeason !in bySeason.keys) selectedSeason = bySeason.keys.firstOrNull() }
    LaunchedEffect(selectedSeason) { if (selectedSeason != null) listState.scrollToItem(0) }
    LaunchedEffect(episodes != null) { if (episodes != null) { kotlinx.coroutines.delay(100); runCatching { firstEpisodeFocus.requestFocus() } } }

    val poster = info?.poster ?: episodes?.firstOrNull { !it.logo.isNullOrBlank() }?.logo
    val seasonEps = selectedSeason?.let { bySeason[it] }.orEmpty()

    fun play(ep: ChannelEntity) {
        val ordered = bySeason.values.flatten()
        val idx = ordered.indexOf(ep)
        val queue = ordered.drop(idx + 1).take(50).map { PlaybackRequest(url = it.url, title = seriesName, subtitle = epLabel(it), isLive = false) }
        onPlay(PlaybackRequest(url = ep.url, title = seriesName, subtitle = epLabel(ep), isLive = false, queue = queue))
    }

    Box(Modifier.fillMaxSize().background(HugoColors.Background)) {
        if (poster != null) SubcomposeAsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.12f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(HugoColors.Background, Color(0xCC07080F), Color(0x9907080F)))))

        Row(Modifier.fillMaxSize().padding(24.dp)) {
            Column(Modifier.width(200.dp)) {
                HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack, label = "Voltar")
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(HugoColors.Card), contentAlignment = Alignment.Center) {
                    if (poster != null) SubcomposeAsyncImage(model = poster, contentDescription = seriesName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text(seriesName, color = HugoColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(seriesName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            isFav = WatchHistory.toggleFavorite(context, favKey)
                            if (isFav) WatchHistory.saveFavoriteMeta(context, favKey, seriesName, "", poster, playlistId)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isFav) Color(0xFFEC4899) else HugoColors.CardAlt),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isFav) "Favorito" else "Favoritar", fontSize = 12.sp)
                    }
                }
                info?.genres?.let { Text(it, color = HugoColors.Accent, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                Text(info?.overview ?: "Sinopse não disponível.", color = HugoColors.TextMuted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(4.dp))
                Text(if (episodes == null) "Carregando..." else "${bySeason.size} temporada(s) · ${episodes!!.size} episódio(s)", color = HugoColors.TextMuted, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))

                if (episodes == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SeriesAccent) }; return@Column }
                if (episodes!!.isEmpty()) { Text("Nenhum episódio encontrado.", color = HugoColors.TextMuted); return@Column }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(bySeason.keys.toList(), key = { it }) { season ->
                        val selected = season == selectedSeason
                        FocusSurface(onClick = { selectedSeason = season }, onFocused = { selectedSeason = season }, selected = selected, selectedColor = SeriesAccent, shape = RoundedCornerShape(50), focusScale = 1.06f, modifier = Modifier.height(36.dp)) { _ ->
                            Row(Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("T$season", color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(seasonEps, key = { i, it -> "${it.id}-$i" }) { index, ep ->
                        FocusSurface(onClick = { play(ep) }, focusScale = 1.01f, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().height(64.dp).then(if (index == 0) Modifier.focusRequester(firstEpisodeFocus) else Modifier)) { focused ->
                            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(80.dp).height(48.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F1220)), contentAlignment = Alignment.Center) {
                                    if (!ep.logo.isNullOrBlank()) SubcomposeAsyncImage(model = ep.logo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = if (focused) 1f else 0.7f), modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ep.episode?.let { "Episódio $it" } ?: "Episódio ${index + 1}", color = if (focused) SeriesAccent else HugoColors.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text(episodeTitle(ep).ifBlank { ep.name }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (focused) Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun epLabel(ep: ChannelEntity): String {
    val s = ep.season ?: 1
    val e = ep.episode
    val code = if (e != null) "T%d · E%02d".format(s, e) else "Temporada $s"
    val t = episodeTitle(ep)
    return if (t.isNotBlank()) "$code · $t" else code
}
