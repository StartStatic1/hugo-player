package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class PlaylistItem(val id: String, val name: String, val type: String)

@Composable
fun PlaylistScreen(
    deviceId: String,
    deviceKey: String,
    onSelect: (String, String) -> Unit,
    onAddNew: () -> Unit
) {
    var playlists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        try {
            val list = fetchPlaylists(deviceId, deviceKey)
            playlists = list
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF07080F)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Suas Playlists", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Selecione uma lista para assistir", color = Color(0xFF94A3B8), fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))

        if (loading) {
            CircularProgressIndicator(color = Color(0xFF8B5CF6))
        } else if (error != null) {
            Text("Erro: $error", color = Color.Red)
        } else if (playlists.isEmpty()) {
            Text("Nenhuma playlist. Adicione uma no site.", color = Color(0xFF94A3B8))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(playlists) { pl ->
                    Card(
                        onClick = { onSelect(pl.id, pl.name) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1D2E)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(100.dp)
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                            Text(pl.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(pl.type, color = Color(0xFF8B5CF6), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAddNew,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
        ) {
            Text("Atualizar lista")
        }
    }
}

suspend fun fetchPlaylists(deviceId: String, deviceKey: String): List<PlaylistItem> = withContext(Dispatchers.IO) {
    val url = URL("http://65.21.48.50:8080/playlists")
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.setRequestProperty("x-device-id", deviceId)
    conn.setRequestProperty("x-device-key", deviceKey)
    conn.connectTimeout = 10000
    conn.readTimeout = 10000
    val text = conn.inputStream.bufferedReader().readText()
    val json = JSONObject(text)
    val arr = json.optJSONArray("playlists") ?: return@withContext emptyList()
    buildList {
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            add(PlaylistItem(
                p.getString("id"),
                p.optString("name", "Playlist"),
                p.optString("type", "M3U")
            ))
        }
    }
}