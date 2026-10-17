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
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playlistId) {
        try {
            val playlistUrl = getPlaylistUrl(playlistId, deviceId, deviceKey)
            if (playlistUrl != null) {
                channels = loadChannelsFromUrl(playlistUrl)
            }
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("TV ao Vivo", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Text("Erro: $error", color = MaterialTheme.colorScheme.error)
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

suspend fun getPlaylistUrl(playlistId: String, deviceId: String, deviceKey: String): String? = withContext(Dispatchers.IO) {
    try {
        val url = URL("http://65.21.48.50:8080/playlists")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("x-device-id", deviceId)
        conn.setRequestProperty("x-device-key", deviceKey)
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

suspend fun loadChannelsFromUrl(playlistUrl: String): List<Channel> = withContext(Dispatchers.IO) {
    try {
        val endpoint = "http://65.21.48.50:8080/channels?url=${java.net.URLEncoder.encode(playlistUrl, "UTF-8")}"
        val text = URL(endpoint).readText()
        val json = JSONObject(text)
        val arr = json.optJSONArray("channels") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length().coerceAtMost(200)) {
                val c = arr.getJSONObject(i)
                add(Channel(c.getString("name"), c.getString("url"), c.optString("logo")))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}