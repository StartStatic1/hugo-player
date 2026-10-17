package com.hugo.player.data

import android.content.Context
import com.hugo.player.data.db.CategoryEntity
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.data.db.HugoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class CatalogRepository(private val context: Context) {
    private val db = HugoDatabase.get(context)
    private val dao = db.channelDao()

    suspend fun syncPlaylist(playlistId: String, playlistUrl: String, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        onProgress("Baixando lista...")
        val encoded = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        val endpoint = "http://65.21.48.50:8080/channels?url=$encoded&limit=50000"
        val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
        val text = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(text)

        onProgress("Salvando no banco...")
        dao.deleteChannels(playlistId)
        dao.deleteCategories(playlistId)

        val chArr = json.optJSONArray("channels") ?: return@withContext
        val channels = mutableListOf<ChannelEntity>()
        for (i in 0 until chArr.length()) {
            val c = chArr.getJSONObject(i)
            channels.add(ChannelEntity(
                name = c.optString("name", "Canal"),
                url = c.getString("url"),
                logo = c.optString("logo").takeIf { it.isNotEmpty() },
                group = c.optString("group", "Outros"),
                type = c.optString("type", "live"),
                playlistId = playlistId
            ))
            if (channels.size >= 500) {
                dao.insertChannels(channels.toList())
                channels.clear()
            }
        }
        if (channels.isNotEmpty()) dao.insertChannels(channels)

        val catArr = json.optJSONArray("categories")
        if (catArr != null) {
            val categories = mutableListOf<CategoryEntity>()
            for (i in 0 until catArr.length()) {
                val c = catArr.getJSONObject(i)
                categories.add(CategoryEntity(
                    name = c.getString("name"),
                    count = c.optInt("count", 0),
                    type = c.optString("type", "live"),
                    playlistId = playlistId
                ))
            }
            dao.insertCategories(categories)
        }
        onProgress("Pronto!")
    }

    suspend fun getChannels(playlistId: String, type: String, group: String? = null, page: Int = 0, pageSize: Int = 50) =
        withContext(Dispatchers.IO) {
            if (group != null) dao.getByTypeAndGroup(playlistId, type, group, pageSize, page * pageSize)
            else dao.getByType(playlistId, type, pageSize, page * pageSize)
        }

    suspend fun getCategories(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.getCategories(playlistId, type) }

    suspend fun count(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.countByType(playlistId, type) }
}
