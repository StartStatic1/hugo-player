package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.db.CategoryEntity
import com.hugo.player.data.db.ChannelEntity
import kotlinx.coroutines.launch

data class SeriesItem(val name: String, val logo: String?, val seasons: Map<Int, List<ChannelEntity>>)

@Composable
fun SeriesScreen(
    playlistId: String,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository(context) }
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var selectedCat by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedSeries by remember { mutableStateOf<SeriesItem?>(null) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val catListState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(playlistId) {
        categories = repo.getCategories(playlistId, "series")
        selectedCat = categories.firstOrNull()?.name
        page = 0
        episodes = if (selectedCat != null) repo.getChannels(playlistId, "series", selectedCat, 0, 200) else emptyList()
        hasMore = episodes.size >= 200
        loading = false
    }

    LaunchedEffect(selectedCat) {
        if (selectedCat != null) {
            loading = true
            page = 0
            episodes = repo.getChannels(playlistId, "series", selectedCat, 0, 200)
            hasMore = episodes.size >= 200
            selectedSeries = null
            selectedSeason = null
            loading = false
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && lastIndex >= episodes.size - 20 && hasMore && !loadingMore && selectedCat != null && selectedSeries == null) {
                    loadingMore = true
                    page++
                    val more = repo.getChannels(playlistId, "series", selectedCat, page, 200)
                    if (more.isNotEmpty()) {
                        episodes = episodes + more
                        hasMore = more.size >= 200
                    } else {
                        hasMore = false
                    }
                    loadingMore = false
                }
            }
    }

    val seriesList = remember(episodes) {
        episodes.groupBy { ep ->
            val name = ep.name
            val patterns = listOf(
                Regex("(.+?)\\s*[Ss](\\d+)\\s*[Ee](\\d+)"),
                Regex("(.+?)\\s*(\\d+)x(\\d+)"),
                Regex("(.+?)\\s*[Tt]emp(?:orada)?\\s*(\\d+)")
            )
            var seriesName = name
            for (p in patterns) {
                val m = p.find(name)
                if (m != null) {
                    seriesName = m.groupValues[1].trim().replace(Regex("[-_]+$"), "").trim()
                    break
                }
            }
            seriesName
        }.map { (name, eps) ->
            val seasonMap = eps.groupBy { ep ->
                val patterns = listOf(
                    Regex("[Ss](\\d+)"),
                    Regex("(\\d+)x"),
                    Regex("[Tt]emp(?:orada)?\\s*(\\d+)")
                )
                for (p in patterns) {
                    val m = p.find(ep.name)
                    if (m != null) return@groupBy m.groupValues[1].toIntOrNull() ?: 1
                }
                1
            }
            SeriesItem(name, eps.firstOrNull()?.logo, seasonMap)
        }.sortedBy { it.name }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF07080F))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    selectedSeason != null -> "${selectedSeries?.name} - T$selectedSeason"
                    selectedSeries != null -> selectedSeries!!.name
                    else -> "Séries (${seriesList.size}${if (hasMore) "+" else ""})"
                },
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Button(onClick = {
                when {
                    selectedSeason != null -> selectedSeason = null
                    selectedSeries != null -> selectedSeries = null
                    else -> onBack()
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))) { Text("Voltar") }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF22C55E)) }
        } else if (selectedSeason != null && selectedSeries != null) {
            val seasonEps = selectedSeries!!.seasons[selectedSeason] ?: emptyList()
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(seasonEps) { ep ->
                    Card(
                        onClick = { onPlay(ep.url, ep.name) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151827)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(160.dp)
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1E2236))) {
                                if (!ep.logo.isNullOrEmpty()) {
                                    AsyncImage(model = ep.logo, contentDescription = ep.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                            }
                            Text(ep.name, color = Color.White, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(2.dp))
                        }
                    }
                }
            }
        } else if (selectedSeries != null) {
            val seasons = selectedSeries!!.seasons.keys.sorted()
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(seasons) { season ->
                    Card(
                        onClick = { selectedSeason = season },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF22C55E)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(80.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Temporada $season", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text("${selectedSeries!!.seasons[season]?.size ?: 0} eps", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else if (categories.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhuma série nesta lista", color = Color(0xFF94A3B8)) }
        } else {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(150.dp).fillMaxHeight().background(Color(0xFF12141C)), state = catListState, contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(categories) { cat ->
                        val selected = cat.name == selectedCat
                        Card(onClick = {
                            selectedCat = cat.name
                            scope.launch {
                                val idx = categories.indexOf(cat)
                                if (idx >= 0) catListState.animateScrollToItem(idx)
                            }
                        }, colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF22C55E) else Color(0xFF1E2236)), shape = RoundedCornerShape(4.dp), modifier = Modifier.height(38.dp)) {
                            Row(Modifier.padding(horizontal = 6.dp).fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${cat.count}", color = Color(0xFF94A3B8), fontSize = 9.sp)
                            }
                        }
                    }
                }
                if (seriesList.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (loadingMore) CircularProgressIndicator(color = Color(0xFF22C55E))
                        else Text("Carregando...", color = Color(0xFF94A3B8))
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(seriesList) { series ->
                            Card(
                                onClick = { selectedSeries = series },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF151827)),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(180.dp)
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1E2236))) {
                                        if (!series.logo.isNullOrEmpty()) {
                                            AsyncImage(model = series.logo, contentDescription = series.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                    Text(series.name, color = Color.White, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
