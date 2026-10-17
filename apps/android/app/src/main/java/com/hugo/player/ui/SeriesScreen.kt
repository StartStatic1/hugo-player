package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

data class SeriesItem(val name: String, val logo: String?, val episodeCount: Int, val episodes: List<ChannelEntity>)

@Composable
fun SeriesScreen(
    playlistId: String,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository(context) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var selectedCat by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selectedSeries by remember { mutableStateOf<SeriesItem?>(null) }

    LaunchedEffect(playlistId) {
        categories = repo.getCategories(playlistId, "series")
        selectedCat = categories.firstOrNull()?.name
        episodes = if (selectedCat != null) repo.getChannels(playlistId, "series", selectedCat, 0, 500) else emptyList()
        loading = false
    }

    LaunchedEffect(selectedCat) {
        if (selectedCat != null) {
            episodes = repo.getChannels(playlistId, "series", selectedCat, 0, 500)
            selectedSeries = null
        }
    }

    // Group episodes into series
    val seriesList = remember(episodes) {
        episodes.groupBy { ep ->
            // Extract series name from episode name
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
            SeriesItem(name, eps.firstOrNull()?.logo, eps.size, eps)
        }.sortedBy { it.name }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF07080F))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedSeries != null) selectedSeries!!.name else "Séries", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { if (selectedSeries != null) selectedSeries = null else onBack() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E))) { Text("Voltar") }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF22C55E)) }
        } else if (selectedSeries != null) {
            // Show episodes of selected series
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(selectedSeries!!.episodes) { ep ->
                    Card(
                        onClick = { onPlay(ep.url, ep.name) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF151827)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(140.dp)
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
        } else if (categories.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhuma série nesta lista", color = Color(0xFF94A3B8)) }
        } else {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(150.dp).fillMaxHeight().background(Color(0xFF12141C)), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(categories) { cat ->
                        val selected = cat.name == selectedCat
                        Card(onClick = { selectedCat = cat.name }, colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF22C55E) else Color(0xFF1E2236)), shape = RoundedCornerShape(4.dp), modifier = Modifier.height(38.dp)) {
                            Row(Modifier.padding(horizontal = 6.dp).fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${cat.count}", color = Color(0xFF94A3B8), fontSize = 9.sp)
                            }
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
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
                            modifier = Modifier.height(150.dp)
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
