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

    companion object {
        @Volatile private var instance: CatalogRepository? = null
        fun init(context: Context) {
            if (instance == null) instance = CatalogRepository(context)
        }
        fun get(context: Context): CatalogRepository =
            instance ?: CatalogRepository(context).also { instance = it }
        fun clearAll() {}
    }

    suspend fun syncPlaylist(
        playlistId: String,
        playlistUrl: String,
        onProgress: (String) -> Unit = {},
        onFraction: (Float?) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        onProgress("Baixando lista...")
        onFraction(null)
        val encoded = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        val endpoint = "http://65.21.48.50:8080/channels?url=$encoded&limit=100000"
        val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 180000
        if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
        val text = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(text)

        onProgress("Salvando no banco...")
        onFraction(0f)
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
            if (channels.size >= 1000) {
                dao.insertChannels(channels.toList())
                channels.clear()
                onProgress("Salvando... ${i}/${chArr.length()}")
                onFraction(i.toFloat() / chArr.length().coerceAtLeast(1))
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
        onFraction(1f)
        onProgress("Pronto! ${chArr.length()} itens")
    }

    /** Apaga do aparelho o catálogo salvo de uma playlist. */
    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        dao.deleteChannels(playlistId)
        dao.deleteCategories(playlistId)
    }

    suspend fun getChannels(playlistId: String, type: String, group: String? = null, page: Int = 0, pageSize: Int = 100) =
        withContext(Dispatchers.IO) {
            if (group != null) dao.getByTypeAndGroup(playlistId, type, group, pageSize, page * pageSize)
            else dao.getByType(playlistId, type, pageSize, page * pageSize)
        }

    suspend fun getCategories(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.getCategories(playlistId, type) }

    suspend fun count(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.countByType(playlistId, type) }
}
