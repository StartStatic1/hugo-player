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
import com.hugo.player.data.CatalogRepository
import kotlinx.coroutines.withTimeoutOrNull

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
            progress = 0.1f
            val playlistUrl = withTimeoutOrNull(15000) { getPlaylistUrlForLoading(playlistId, deviceId, deviceKey) }
            if (playlistUrl == null) {
                status = "Lista não encontrada"
                kotlinx.coroutines.delay(1500)
                onError()
                return@LaunchedEffect
            }
            status = "Sincronizando $playlistName..."
            progress = 0.3f
            val repo = CatalogRepository(context)
            withTimeoutOrNull(180000) {
                repo.syncPlaylist(playlistId, playlistUrl) { msg ->
                    status = msg
                    progress += 0.1f
                }
            }
            LiveTvCache.playlistId = playlistId
            LiveTvCache.playlistName = playlistName
            LiveTvCache.playlistUrl = playlistUrl
            LiveTvCache.save(context)
            status = "Pronto!"
            progress = 1f
            kotlinx.coroutines.delay(400)
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
            CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = Color(0xFF8B5CF6), modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color(0xFF94A3B8), fontSize = 14.sp)
        }
    }
}

suspend fun getPlaylistUrlForLoading(playlistId: String, deviceId: String, deviceKey: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val url = java.net.URL("http://65.21.48.50:8080/playlists")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("x-device-id", deviceId)
        conn.setRequestProperty("x-device-key", deviceKey)
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        if (conn.responseCode !in 200..299) return@withContext null
        val text = conn.inputStream.bufferedReader().readText()
        val json = org.json.JSONObject(text)
        val arr = json.optJSONArray("playlists") ?: return@withContext null
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            if (p.getString("id") == playlistId) return@withContext p.getString("url")
        }
        null
    } catch (e: Exception) { null }
}
