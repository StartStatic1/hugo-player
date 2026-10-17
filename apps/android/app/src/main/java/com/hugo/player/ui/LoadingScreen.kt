package com.hugo.player.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URL

@Composable
fun LoadingScreen(
    playlistId: String,
    playlistName: String,
    deviceId: String,
    deviceKey: String,
    onLoaded: () -> Unit,
    onError: () -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Preparando...") }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(playlistId) {
        try {
            status = "Buscando lista..."
            progress = 0.2f
            val playlistUrl = withTimeoutOrNull(15000) { getPlaylistUrlForLoading(playlistId, deviceId, deviceKey) }
            if (playlistUrl == null) {
                status = "Lista n\u00e3o encontrada"
                kotlinx.coroutines.delay(1500)
                onError()
                return@LaunchedEffect
            }
            status = "Carregando canais de $playlistName..."
            progress = 0.5f
            val result = withTimeoutOrNull(50000) { loadChannelsFromUrl(playlistUrl) }
            if (result == null) {
                status = "Timeout ao carregar"
                kotlinx.coroutines.delay(1500)
                onError()
                return@LaunchedEffect
            }
            LiveTvCache.channels = result.first
            LiveTvCache.categories = result.second
            LiveTvCache.selectedCategory = result.second.firstOrNull()?.name
            LiveTvCache.playlistId = playlistId
            LiveTvCache.playlistName = playlistName
            LiveTvCache.save(context)
            val live = result.first.count { it.type == "live" }
            val movies = result.first.count { it.type == "movie" }
            status = "Pronto! $live ao vivo, $movies filmes"
            progress = 1f
            kotlinx.coroutines.delay(600)
            onLoaded()
        } catch (e: Exception) {
            status = "Erro: ${e.message}"
            kotlinx.coroutines.delay(1500)
            onError()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF07080F)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(playlistName, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("M3U", color = Color(0xFF8B5CF6), fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(progress = { progress }, color = Color(0xFF8B5CF6), modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color(0xFF94A3B8), fontSize = 14.sp)
        }
    }
}

suspend fun getPlaylistUrlForLoading(playlistId: String, deviceId: String, deviceKey: String): String? = withContext(Dispatchers.IO) {
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