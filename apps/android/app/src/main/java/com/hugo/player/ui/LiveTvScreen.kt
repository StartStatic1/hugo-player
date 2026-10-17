package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.db.CategoryEntity
import com.hugo.player.data.db.ChannelEntity

@Composable
fun LiveTvScreen(
    playlistId: String,
    deviceId: String,
    deviceKey: String,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { CatalogRepository(context) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var channels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var selectedCat by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(playlistId) {
        categories = repo.getCategories(playlistId, "live")
        selectedCat = categories.firstOrNull()?.name
        channels = if (selectedCat != null) repo.getChannels(playlistId, "live", selectedCat, 0, 100) else emptyList()
        loading = false
    }

    LaunchedEffect(selectedCat) {
        if (selectedCat != null) {
            channels = repo.getChannels(playlistId, "live", selectedCat, 0, 100)
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF07080F))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("TV ao Vivo", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                LiveTvCache.playlistName?.let { Text(it, color = Color(0xFF8B5CF6), fontSize = 12.sp) }
            }
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))) { Text("Voltar") }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF8B5CF6)) }
        } else if (categories.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum canal ao vivo", color = Color(0xFF94A3B8)) }
        } else {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(190.dp).fillMaxHeight().background(Color(0xFF12141C)), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(categories) { cat ->
                        val selected = cat.name == selectedCat
                        Card(onClick = { selectedCat = cat.name }, colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF8B5CF6) else Color(0xFF1E2236)), shape = RoundedCornerShape(6.dp), modifier = Modifier.height(44.dp)) {
                            Row(Modifier.padding(horizontal = 8.dp).fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${cat.count}", color = Color(0xFF94A3B8), fontSize = 10.sp)
                            }
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(channels) { ch ->
                        Card(onClick = { onPlay(ch.url, ch.name) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF151827)), shape = RoundedCornerShape(6.dp), modifier = Modifier.height(48.dp)) {
                            Row(Modifier.padding(horizontal = 12.dp).fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                Text(ch.name, color = Color.White, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
