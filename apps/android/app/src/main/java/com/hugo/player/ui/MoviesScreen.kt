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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.db.CategoryEntity
import com.hugo.player.data.db.ChannelEntity

@Composable
fun MoviesScreen(
    playlistId: String,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository(context) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var movies by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var selectedCat by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(0) }

    LaunchedEffect(playlistId) {
        categories = repo.getCategories(playlistId, "movie")
        selectedCat = categories.firstOrNull()?.name
        movies = if (selectedCat != null) repo.getChannels(playlistId, "movie", selectedCat, 0, 100) else emptyList()
        loading = false
    }

    LaunchedEffect(selectedCat) {
        if (selectedCat != null) {
            page = 0
            movies = repo.getChannels(playlistId, "movie", selectedCat, 0, 100)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF07080F))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Filmes", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC4899))) { Text("Voltar") }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFFEC4899)) }
        } else if (categories.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum filme nesta lista", color = Color(0xFF94A3B8)) }
        } else {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(160.dp).fillMaxHeight().background(Color(0xFF12141C)), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(categories) { cat ->
                        val selected = cat.name == selectedCat
                        Card(onClick = { selectedCat = cat.name }, colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFEC4899) else Color(0xFF1E2236)), shape = RoundedCornerShape(6.dp), modifier = Modifier.height(42.dp)) {
                            Row(Modifier.padding(horizontal = 8.dp).fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.name, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${cat.count}", color = Color(0xFF94A3B8), fontSize = 10.sp)
                            }
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(movies) { ch ->
                        Card(
                            onClick = { onPlay(ch.url, ch.name) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151827)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(160.dp)
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1E2236))) {
                                    if (!ch.logo.isNullOrEmpty()) {
                                        AsyncImage(model = ch.logo, contentDescription = ch.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    }
                                }
                                Text(ch.name, color = Color.White, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
