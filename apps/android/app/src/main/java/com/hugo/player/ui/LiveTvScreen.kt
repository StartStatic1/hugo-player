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
        channels = loadChannels(playlistId, deviceId, deviceKey)
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("TV ao Vivo", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator()
        } else if (channels.isEmpty()) {
            Text("Nenhum canal encontrado nesta playlist")
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

suspend fun loadChannels(playlistId: String, deviceId: String, deviceKey: String): List<Channel> = withContext(Dispatchers.IO) {
    try {
        // Placeholder: in real implementation this would parse the M3U from the playlist URL stored on server
        // For now returns empty until the backend exposes channel list endpoint
        emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}