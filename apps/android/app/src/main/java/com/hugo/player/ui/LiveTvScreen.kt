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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URL

data class Channel(val name: String, val url: String, val logo: String?, val group: String)
data class Category(val name: String, val count: Int)

@Composable
fun LiveTvScreen(
    playlistId: String,
    deviceId: String,
    deviceKey: String,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var allChannels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(allChannels.isEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var debugInfo by remember { mutableStateOf("") }

    LaunchedEffect(playlistId) {
        if (allChannels.isNotEmpty()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        debugInfo = "Buscando playlist..."
        try {
            val playlistUrl = withTimeoutOrNull(15000) {
                getPlaylistUrl(playlistId, deviceId, deviceKey)
            }
            debugInfo = "URL: ${playlistUrl ?: "null"}"
            if (playlistUrl != null) {
                debugInfo = "Carregando canais..."
                val result = withTimeoutOrNull(45000) {
                    loadChannelsFromUrl(playlistUrl)
                }
                if (result != null) {
                    allChannels = result.first
                    categories = result.second
                    selectedCategory = categories.firstOrNull()?.name
                    debugInfo = "${allChannels.size} canais"
                } else {
                    error = "Timeout ao carregar"
                }
            } else {
                error = "Playlist n\u00e3o encontrada"
            }
        } catch (e: Exception) {
            error = e.message ?: "Erro"
        }
        loading = false
    }

    val filtered = remember(allChannels, selectedCategory) {
        if (selectedCategory == null) allChannels
        else allChannels.filter { it.group == selectedCategory }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF07080F))) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TV ao Vivo", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))) {
                Text("Voltar")
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF8B5CF6))
                    Spacer(Modifier.height(12.dp))
                    Text(debugInfo, color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            }
        } else if (error != null) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Erro: $error", color = Color.Red)
            }
        } else if (allChannels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum canal", color = Color(0xFF94A3B8))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.width(200.dp).fillMaxHeight().background(Color(0xFF12141C)),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(categories) { cat ->
                        val selected = cat.name == selectedCategory
                        Card(
                            onClick = { selectedCategory = cat.name },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) Color(0xFF8B5CF6) else Color(0xFF1E2236)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp).fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(cat.name, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                Text("${cat.count}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            }
                        }
                    }
                }

                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filtered) { ch ->
                        Card(
                            onClick = { onPlay(ch.url, ch.name) },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151827)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp).fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ch.name, color = Color.White, fontSize = 14.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

suspend fun getPlaylistUrl(playlistId: String, deviceId: String, deviceKey: String): String? = withContext(Dispatchers.IO) {
    try {
        val url = URL("http://65.21.48.50:8080/playlists")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("x-device-id", deviceId)
        conn.setRequestProperty("x-device-key", deviceKey)
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        if (conn.responseCode !in 200..299) return@withContext null
        val text = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(text)
        val arr = json.optJSONArray("playlists") ?: return@withContext null
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.getString("id") == playlistId) return@withContext p.getString("url")
        }
        null
    } catch (e: Exception) { null }
}

suspend fun loadChannelsFromUrl(playlistUrl: String): Pair<List<Channel>, List<Category>> = withContext(Dispatchers.IO) {
    val encoded = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
    val endpoint = "http://65.21.48.50:8080/channels?url=$encoded"
    val url = URL(endpoint)
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 20000
    conn.readTimeout = 40000
    if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
    val text = conn.inputStream.bufferedReader().readText()
    val json = JSONObject(text)

    val chArr = json.optJSONArray("channels") ?: return@withContext Pair(emptyList(), emptyList())
    val channels = buildList {
        for (i in 0 until chArr.length().coerceAtMost(600)) {
            val c = chArr.getJSONObject(i)
            add(Channel(
                c.optString("name", "Canal"),
                c.getString("url"),
                c.optString("logo").takeIf { it.isNotEmpty() },
                c.optString("group", "Outros")
            ))
        }
    }

    val catArr = json.optJSONArray("categories")
    val categories = if (catArr != null) {
        buildList {
            for (i in 0 until catArr.length()) {
                val c = catArr.getJSONObject(i)
                add(Category(c.getString("name"), c.optInt("count", 0)))
            }
        }
    } else {
        channels.groupBy { it.group }.map { Category(it.key, it.value.size) }
    }

    Pair(channels, categories)
}