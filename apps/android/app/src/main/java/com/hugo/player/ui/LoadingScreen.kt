package com.hugo.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.hugo.player.data.AppSettings
import com.hugo.player.data.CatalogRepository
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
    var status by remember { mutableStateOf("Preparando...") }
    var progress by remember { mutableStateOf(0f) }

    LaunchedEffect(playlistId) {
        try {
            status = "Buscando lista..."
            progress = 0.2f
            // Com cache em disco não precisa checar a lista na rede (abre offline também)
            val hasCache = CatalogRepository.hasDiskCache(playlistId)
            val playlistUrl = if (hasCache) "cache" else withTimeoutOrNull(15000) {
                getPlaylistUrlForLoading(playlistId, deviceId, deviceKey)
            }
            if (playlistUrl == null) {
                status = "Lista n\u00e3o encontrada"
                kotlinx.coroutines.delay(1500)
                onError()
                return@LaunchedEffect
            }

            status = if (CatalogRepository.hasFreshDiskCache(playlistId)) "Abrindo do cache..." else "Carregando conteúdo de $playlistName..."
            progress = 0.5f
            CatalogRepository.playlistName = playlistName
            // Catálogo separado (live / filmes / séries) pelo backend
            val catalog = try {
                withTimeoutOrNull(120_000) {
                    CatalogRepository.load(playlistId, deviceId, deviceKey)
                }
            } catch (e: Exception) {
                status = e.message ?: "Erro ao carregar"
                kotlinx.coroutines.delay(2500)
                onError()
                return@LaunchedEffect
            }
            if (catalog == null) {
                status = "Timeout ao carregar"
                kotlinx.coroutines.delay(1500)
                onError()
                return@LaunchedEffect
            }
            CatalogRepository.playlistName = playlistName
            AppSettings.lastPlaylistId = playlistId
            AppSettings.lastPlaylistName = playlistName

            // Mantém o cache antigo preenchido (compatibilidade)
            LiveTvCache.channels = catalog.live.items.map { Channel(it.name, it.url, it.logo, it.group, "live") }
            LiveTvCache.categories = catalog.live.categories.map { Category(it.name, it.count, "live") }
            LiveTvCache.selectedCategory = LiveTvCache.categories.firstOrNull()?.name
            LiveTvCache.playlistId = playlistId
            LiveTvCache.playlistName = playlistName

            if (CatalogRepository.origin == CatalogRepository.Origin.STALE_DISK) {
                status = "Sem conexão com o servidor — usando a lista salva"
                kotlinx.coroutines.delay(1500)
            }
            status = "Pronto! ${catalog.live.items.size} canais · ${catalog.movies.items.size} filmes · ${catalog.series.items.size} séries"
            progress = 1f
            kotlinx.coroutines.delay(700)
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
            Text("Live · Filmes · Séries", color = Color(0xFF8B5CF6), fontSize = 14.sp)
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(
                progress = { progress },
                color = Color(0xFF8B5CF6),
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color(0xFF94A3B8), fontSize = 14.sp)
        }
    }
}

suspend fun getPlaylistUrlForLoading(playlistId: String, deviceId: String, deviceKey: String): String? = withContext(Dispatchers.IO) {
    try {
        val url = URL("${com.hugo.player.BuildConfig.API_BASE_URL}/playlists")
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