package com.hugo.player.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

// Funções da primeira versão (endpoint /channels). Mantidos por
// compatibilidade; as telas novas usam data/CatalogRepository (/catalog).

// Channel, Category e LiveTvCache ficam em LiveTvCache.kt

suspend fun getPlaylistUrl(playlistId: String, deviceId: String, deviceKey: String): String? = withContext(Dispatchers.IO) {
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

suspend fun loadChannelsFromUrl(playlistUrl: String): Pair<List<Channel>, List<Category>> = withContext(Dispatchers.IO) {
    val encoded = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
    val endpoint = "${com.hugo.player.BuildConfig.API_BASE_URL}/channels?url=$encoded"
    val url = URL(endpoint)
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 20000
    conn.readTimeout = 40000
    if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
    val text = conn.inputStream.bufferedReader().readText()
    val json = JSONObject(text)

    val chArr = json.optJSONArray("channels") ?: return@withContext Pair(emptyList(), emptyList())
    val channels = buildList {
        for (i in 0 until chArr.length().coerceAtMost(800)) {
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
