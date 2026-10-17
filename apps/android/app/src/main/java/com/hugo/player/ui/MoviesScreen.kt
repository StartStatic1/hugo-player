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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MoviesScreen(
    playlistId: String,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val movieChannels = remember { LiveTvCache.channels.filter { it.type == "movie" } }
    val movieCategories = remember { LiveTvCache.categories.filter { it.type == "movie" } }
    var selectedCat by remember { mutableStateOf(movieCategories.firstOrNull()?.name) }

    val filtered = remember(movieChannels, selectedCat) {
        if (selectedCat == null) movieChannels else movieChannels.filter { it.group == selectedCat }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF07080F))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Filmes", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEC4899))) { Text("Voltar") }
        }
        if (movieChannels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum filme nesta lista", color = Color(0xFF94A3B8))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(190.dp).fillMaxHeight().background(Color(0xFF12141C)), contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(movieCategories) { cat ->
                        val selected = cat.name == selectedCat
                        Card(onClick = { selectedCat = cat.name }, colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFEC4899) else Color(0xFF1E2236)), shape = RoundedCornerShape(6.dp), modifier = Modifier.height(44.dp)) {
                            Row(Modifier.padding(horizontal = 8.dp).fillMaxSize(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.name, color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                Text("${cat.count}", color = Color(0xFF94A3B8), fontSize = 10.sp)
                            }
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered) { ch ->
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