package com.hugo.player.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class PlaylistCard(val id: String, val name: String, val type: String)

@Composable
fun PlaylistScreen(
    deviceId: String,
    deviceKey: String,
    onSelect: (String) -> Unit,
    onAddNew: () -> Unit
) {
    var playlists by remember { mutableStateOf<List<PlaylistCard>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        scope.launch {
            playlists = loadPlaylists(deviceId, deviceKey)
            loading = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1A0A2E), Color(0xFF07080F))))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Suas Playlists", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Selecione uma lista para assistir", color = Color(0xFF94A3B8), fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))

            if (loading) {
                CircularProgressIndicator(color = Color(0xFF8B5CF6))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(playlists) { pl ->
                        Card(
                            onClick = { onSelect(pl.id) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B))
                        ) {
                            Column(Modifier.padding(20.dp)) {
                                Text(pl.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Text(pl.type.uppercase(), color = Color(0xFF8B5CF6), fontSize = 12.sp)
                            }
                        }
                    }
                    item {
                        Card(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://65.21.48.50:3000"))
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
                        ) {
                            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                                Text("+ Adicionar", color = Color(0xFF8B5CF6), fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

suspend fun loadPlaylists(deviceId: String, deviceKey: String): List<PlaylistCard> = withContext(Dispatchers.IO) {
    try {
        val url = URL("http://65.21.48.50:8080/playlists")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("x-device-id", deviceId)
        conn.setRequestProperty("x-device-key", deviceKey)
        val text = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(text)
        val arr = json.optJSONArray("playlists") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                add(PlaylistCard(p.getString("id"), p.getString("name"), p.optString("type", "m3u")))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}