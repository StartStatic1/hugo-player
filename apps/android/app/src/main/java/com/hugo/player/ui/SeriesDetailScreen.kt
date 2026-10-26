package com.hugo.player.ui

import com.hugo.player.ui.components.lazyFocusRestore
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Check
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

internal fun episodeTitle(ep: ChannelEntity): String = cleanEpisodeTitle(ep.name, ep.seriesName)

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
    // sinopse/duração por URL do episódio (quando o servidor manda)
    var meta by remember { mutableStateOf<Map<String, EpisodeMeta>>(emptyMap()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedSeason by rememberSaveable { mutableStateOf<Int?>(null) }
    var info by remember { mutableStateOf<TmdbClient.Info?>(null) }
    val favKey = remember(seriesName) { "series:$playlistId:$seriesName" }
    var isFav by remember { mutableStateOf(WatchHistory.isFavorite(context, favKey)) }
    val firstEpisodeFocus = remember { FocusRequester() }
    var isWide by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // versões dublada/legendada (mesma série) — a escolha fica guardada por série
    var twin by remember { mutableStateOf<com.hugo.player.data.db.SeriesTwinEntity?>(null) }
    var activeName by rememberSaveable(seriesName) { mutableStateOf<String?>(null) }
    LaunchedEffect(playlistId, seriesName) {
        val t = runCatching { repo.seriesTwinOf(playlistId, seriesName) }.getOrNull()
        twin = t
        if (activeName == null) activeName = t?.let { SeriesVersionPrefs.choice(context, playlistId, it) } ?: seriesName
    }
    LaunchedEffect(seriesName) { info = TmdbClient.searchTv(seriesName) }

    LaunchedEffect(playlistId, activeName) {
        val name = activeName ?: return@LaunchedEffect
        episodes = null; meta = emptyMap(); errorMsg = null
        val local = withContext(Dispatchers.IO) { repo.episodes(playlistId, name) }
        val marker = local.firstOrNull()?.url
        if (marker != null && marker.startsWith("xtream://series/")) {
            val seriesId = marker.removePrefix("xtream://series/")
            // só o id da playlist + aparelho: o servidor usa o link guardado (sem usuário/senha na URL)
            val identity = com.hugo.player.data.DeviceIdentity.get(context)
            val endpoint = "${com.hugo.player.data.HugoApi.baseUrl}/channels/series-episodes?playlistId=${java.net.URLEncoder.encode(playlistId, "UTF-8")}&seriesId=$seriesId"
            try {
                val body = withContext(Dispatchers.IO) {
                    val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("x-device-id", identity.deviceId)
                    conn.setRequestProperty("x-device-key", identity.deviceKey)
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
                val metaOut = HashMap<String, EpisodeMeta>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val plot = e.optString("plot").takeIf { it.isNotBlank() && it != "null" }
                        val dur = e.optInt("durationSecs", 0).takeIf { it > 0 }
                        if (plot != null || dur != null) metaOut[e.getString("url")] = EpisodeMeta(plot, dur)
                        list.add(ChannelEntity(name = e.optString("name", name), url = e.getString("url"), logo = e.optString("logo").takeIf { it.isNotEmpty() }, group = "", type = "series", playlistId = playlistId, seriesName = name, season = e.optInt("season", 1), episode = e.optInt("episode", i + 1)))
                    }
                }
                meta = metaOut
                episodes = list
            } catch (ex: Exception) {
                errorMsg = "Não deu para carregar os episódios do servidor" + (if (local.size > 1) " — mostrando os salvos." else ".") +
                    " (" + (ex.message?.substringBefore(':')?.take(40) ?: ex.javaClass.simpleName) + ")"
                episodes = local
            }
        } else episodes = local
    }
    val bySeason = remember(episodes) { episodes.orEmpty().groupBy { it.season ?: 1 }.toSortedMap() }
    LaunchedEffect(bySeason) { if (selectedSeason == null || selectedSeason !in bySeason.keys) selectedSeason = bySeason.keys.firstOrNull() }
    LaunchedEffect(selectedSeason) { if (selectedSeason != null) listState.scrollToItem(0) }
    // TV/tela larga: começa no 1º episódio; celular em pé não (o topo sumiria)
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(episodes != null) {
        // só na 1ª carga (trocar Dublado/Legendado não tira o foco do botão)
        if (episodes != null && !initialFocusDone) { initialFocusDone = true; kotlinx.coroutines.delay(100); if (isWide) runCatching { firstEpisodeFocus.requestFocus() } }
    }

    val poster = info?.poster ?: episodes?.firstOrNull { !it.logo.isNullOrBlank() }?.logo
    val seasonEps = selectedSeason?.let { bySeason[it] }.orEmpty()

    // assistidos / progresso parcial — relê ao voltar do player
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val watched = remember(resumeTick) { WatchHistory.watchedKeys(context) }
    val partial = remember(resumeTick) { WatchHistory.progressFractions(context) }

    val shownName = activeName ?: seriesName

    fun play(ep: ChannelEntity) {
        val ordered = bySeason.values.flatten()
        val idx = ordered.indexOf(ep)
        // playlistId + pôster também nos próximos episódios (senão somem do "Continuar assistindo")
        val queue = ordered.drop(idx + 1).take(50).map { PlaybackRequest(url = it.url, title = shownName, subtitle = epLabel(it), isLive = false, playlistId = playlistId, logo = poster) }
        onPlay(PlaybackRequest(url = ep.url, title = shownName, subtitle = epLabel(ep), isLive = false, queue = queue, playlistId = playlistId, logo = poster))
    }

    // foco: última linha de episódio focada (restauração manual, sem focusRestorer)
    var lastEp by rememberSaveable(selectedSeason) { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }
    var synopsisOpen by rememberSaveable { mutableStateOf(false) }
    val seasonList = selectedSeason?.let { bySeason[it] }.orEmpty()
    val firstUnwatched = seasonList.firstOrNull { WatchHistory.contentKey(playlistId, it.url) !in watched } ?: seasonList.firstOrNull()
    val episodeVisible = { runCatching { listState.layoutInfo.visibleItemsInfo.any { it.index == lastEp } }.getOrDefault(false) }

    @Composable
    fun Actions(modifier: Modifier = Modifier) {
        val playSrc = remember { MutableInteractionSource() }
        val favSrc = remember { MutableInteractionSource() }
        val playFocused by playSrc.collectIsFocusedAsState()
        val favFocused by favSrc.collectIsFocusedAsState()
        // → vai para os episódios (o último focado, se estiver na tela)
        val toEpisodes = if (!isWide) Modifier else Modifier.focusProperties { right = if (episodeVisible()) firstEpisodeFocus else FocusRequester.Default }
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { firstUnwatched?.let { play(it) } },
                enabled = firstUnwatched != null,
                colors = ButtonDefaults.buttonColors(containerColor = SeriesAccent, contentColor = Color.White),
                interactionSource = playSrc,
                border = if (playFocused) BorderStroke(2.dp, Color.White) else null,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.focusRequester(playFocus).then(toEpisodes),
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(firstUnwatched?.let { "Assistir T${it.season ?: 1} E${it.episode ?: 1}" } ?: "Assistir", fontSize = 13.sp, maxLines = 1)
            }
            Button(
                onClick = {
                    isFav = WatchHistory.toggleFavorite(context, favKey)
                    if (isFav) WatchHistory.saveFavoriteMeta(context, favKey, seriesName, "", poster, playlistId)
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (isFav) Color(0xFFEC4899) else Color(0xFF2A2140), contentColor = Color.White),
                interactionSource = favSrc,
                border = if (favFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0xFFEC4899)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = toEpisodes,
            ) {
                Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isFav) "Favorito" else "Favoritar", fontSize = 13.sp, maxLines = 1)
            }
        }
    }

    @Composable
    fun Info(posterWidth: androidx.compose.ui.unit.Dp) {
        Row {
            Box(Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(HugoColors.Card), contentAlignment = Alignment.Center) {
                if (poster != null) SubcomposeAsyncImage(model = poster, contentDescription = shownName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(shownName, color = HugoColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(shownName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                info?.genres?.let { Text(it, color = HugoColors.Accent, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                Text(if (episodes == null) "Carregando..." else "${bySeason.size} temporada(s) · ${episodes!!.size} episódio(s)", color = HugoColors.TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // sinopse: 2 linhas; OK/toque abre inteira
        FocusSurface(
            onClick = { synopsisOpen = !synopsisOpen },
            color = Color.Transparent, focusedColor = Color(0x22FFFFFF), focusScale = 1f, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { focused ->
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(info?.overview ?: "Sinopse não disponível.", color = HugoColors.TextMuted, fontSize = 12.sp, maxLines = if (synopsisOpen) 30 else 2, overflow = TextOverflow.Ellipsis)
                if (info?.overview != null) Text(if (synopsisOpen) "mostrar menos" else "mais…", color = if (focused) Color.White else SeriesAccent, fontSize = 11.sp)
            }
        }
        errorMsg?.let { Text(it, color = Color(0xFFFCA5A5), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(8.dp))
        Actions()
    }

    @Composable
    fun Versions(leftToActions: Boolean) {
        val t = twin ?: return
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            listOf("Dublado" to t.dubName, "Legendado" to t.legName).forEach { (label, name) ->
                val selected = activeName == name
                FocusSurface(
                    onClick = {
                        if (!selected) {
                            SeriesVersionPrefs.save(context, playlistId, t, name)
                            selectedSeason = null; lastEp = 0
                            activeName = name
                        }
                    },
                    selected = selected, selectedColor = SeriesAccent, color = HugoColors.CardAlt,
                    focusedColor = SeriesAccent.copy(alpha = 0.55f), focusBorderWidth = 3.dp, focusScale = 1.04f,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(34.dp).then(if (leftToActions) Modifier.focusProperties { left = playFocus } else Modifier),
                ) { _ ->
                    Row(Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }

    @Composable
    fun Seasons() {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(bySeason.keys.toList(), key = { it }) { season ->
                val selected = season == selectedSeason
                FocusSurface(onClick = { selectedSeason = season }, onFocused = { selectedSeason = season }, selected = selected, selectedColor = SeriesAccent, shape = RoundedCornerShape(50), focusScale = 1.06f, modifier = Modifier.height(34.dp)) { _ ->
                    Row(Modifier.fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("T$season", color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }

    @Composable
    fun EpisodeRow(index: Int, ep: ChannelEntity, leftToActions: Boolean, plotLines: Int = 2) {
        val m = meta[ep.url]
        val epKey = WatchHistory.contentKey(playlistId, ep.url)
        val isWatched = epKey in watched
        val frac = partial[epKey]
        val number = ep.episode ?: (index + 1)
        // título útil: o do provedor sem o nome da série; se não sobrar nada, "Episódio N"
        val title = episodeTitle(ep).ifBlank { "Episódio $number" }
        val header = listOfNotNull("T${ep.season ?: 1} · E$number", formatEpisodeDuration(m?.durationSecs), if (isWatched) "Assistido" else frac?.let { "${(it * 100).toInt()}%" }).joinToString(" · ")
        FocusSurface(
            onClick = { play(ep) },
            onFocused = { lastEp = index },
            focusScale = 1.01f, focusBorderWidth = 3.dp, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .then(if (index == lastEp) Modifier.focusRequester(firstEpisodeFocus) else Modifier)
                .then(if (leftToActions) Modifier.focusProperties { left = playFocus } else Modifier),
        ) { focused ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF0F1220)), contentAlignment = Alignment.Center) {
                    val thumb = ep.logo?.takeIf { it.isNotBlank() } ?: poster
                    if (!thumb.isNullOrBlank()) SubcomposeAsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = if (focused) 1f else 0.7f), modifier = Modifier.size(20.dp))
                    if (isWatched) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp).background(Color(0xFF16A34A), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, "Assistido", tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                    if (frac != null && !isWatched) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(SeriesAccent))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(header, color = if (focused) SeriesAccent else HugoColors.TextMuted, fontSize = 11.sp, maxLines = 1)
                    m?.plot?.let { Text(it, color = HugoColors.TextMuted, fontSize = 11.sp, maxLines = plotLines, overflow = TextOverflow.Ellipsis) }
                }
                if (focused) Icon(Icons.Default.PlayArrow, null, tint = Color.White)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(HugoColors.Background)) {
        if (poster != null) SubcomposeAsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.12f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(HugoColors.Background, Color(0xCC07080F), Color(0x9907080F)))))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val plotLines = if (maxHeight < 480.dp) 1 else 2
            SideEffect { isWide = wide }
            if (wide) {
                // duas colunas: info à esquerda (rola sozinha), temporadas + episódios com a altura toda à direita
                val leftWidth = (maxWidth * 0.36f).coerceIn(260.dp, 400.dp)
                val posterWidth = if (maxHeight >= 520.dp) 130.dp else 92.dp
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Column(Modifier.width(leftWidth).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack, label = "Voltar")
                        Spacer(Modifier.height(10.dp))
                        Info(posterWidth)
                        Spacer(Modifier.height(12.dp))
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        when {
                            episodes == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SeriesAccent) }
                            episodes!!.isEmpty() -> Text("Nenhum episódio encontrado.", color = HugoColors.TextMuted, modifier = Modifier.padding(top = 12.dp))
                            else -> {
                                Versions(leftToActions = true)
                                Seasons()
                                Spacer(Modifier.height(6.dp))
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize().lazyFocusRestore(listState, firstEpisodeFocus) { lastEp },
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    contentPadding = PaddingValues(bottom = 16.dp),
                                ) {
                                    itemsIndexed(seasonEps, key = { i, it -> "${it.id}-$i" }) { index, ep -> EpisodeRow(index, ep, leftToActions = true, plotLines = plotLines) }
                                }
                            }
                        }
                    }
                }
            } else {
                // celular em pé: uma coluna só, o topo rola junto
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().lazyFocusRestore(listState, firstEpisodeFocus) { lastEp + 2 },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item(key = "__info") {
                        Column {
                            HugoIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", onClick = onBack, label = "Voltar")
                            Spacer(Modifier.height(10.dp))
                            Info(96.dp)
                        }
                    }
                    item(key = "__seasons") {
                        when {
                            episodes == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SeriesAccent) }
                            episodes!!.isEmpty() -> Text("Nenhum episódio encontrado.", color = HugoColors.TextMuted)
                            else -> Column { Versions(leftToActions = false); Seasons() }
                        }
                    }
                    itemsIndexed(seasonEps, key = { i, it -> "${it.id}-$i" }) { index, ep -> EpisodeRow(index, ep, leftToActions = false) }
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

/** Última versão (dublada/legendada) escolhida em cada série; sem escolha = a versão aberta (card junto = dublada). */
object SeriesVersionPrefs {
    private fun prefs(c: android.content.Context) = c.applicationContext.getSharedPreferences("hugo_series_ver", android.content.Context.MODE_PRIVATE)
    private fun key(playlistId: String, t: com.hugo.player.data.db.SeriesTwinEntity) = "$playlistId|${t.dubName}"
    fun choice(c: android.content.Context, playlistId: String, t: com.hugo.player.data.db.SeriesTwinEntity): String? =
        when (prefs(c).getString(key(playlistId, t), null)) { "leg" -> t.legName; "dub" -> t.dubName; else -> null }
    fun save(c: android.content.Context, playlistId: String, t: com.hugo.player.data.db.SeriesTwinEntity, name: String) {
        prefs(c).edit().putString(key(playlistId, t), if (name == t.legName) "leg" else "dub").apply()
    }
}
