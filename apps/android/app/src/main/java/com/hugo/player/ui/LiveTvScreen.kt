package com.hugo.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class Channel(val name: String, val url: String, val logo: String?)

@Composable
fun LiveTvScreen(
    playlistId: String,
    deviceId: String,
    deviceKey: String,
    onPlay: (String, String) -> Unit,
    onBack: () -> Unit
) {
    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(playlistId) {
        channels = loadChannels(playlistId)
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("TV ao Vivo", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator()
        } else if (channels.isEmpty()) {
            Text("Nenhum canal encontrado")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(channels) { ch ->
                    Card(onClick = { onPlay(ch.url, ch.name) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(ch.name, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Voltar") }
    }
}

suspend fun loadChannels(playlistId: String): List<Channel> = withContext(Dispatchers.IO) {
    try {
        // For now expects the playlist URL to be passed or fetched. 
        // In production the backend would resolve playlistId -> url
        val url = "http://65.21.48.50:8080/channels?url=https://example.com/playlist.m3u"
        val text = URL(url).readText()
        val json = JSONObject(text)
        val arr = json.optJSONArray("channels") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                add(Channel(c.getString("name"), c.getString("url"), c.optString("logo")))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}