package com.hugo.player.data

import android.content.Context
import com.hugo.player.data.db.CategoryEntity
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.data.db.HugoDatabase
import com.hugo.player.data.db.SeriesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.JsonReader
import java.net.URL

/** Extrai nome da série / temporada / episódio de títulos como "Série S01E02". */
object SeriesParser {
    data class Info(val seriesName: String, val season: Int, val episode: Int?)

    private val patterns = listOf(
        Regex("(.+?)[\\s._-]*[Ss](\\d{1,2})[\\s._-]*[Ee](\\d{1,3})"),
        Regex("(.+?)\\s+(\\d{1,2})x(\\d{1,3})\\b"),
        Regex("(.+?)\\s*[Tt]emp(?:orada)?\\.?\\s*(\\d{1,2})\\s*[Ee]p(?:is[oó]dio)?\\.?\\s*(\\d{1,3})"),
        Regex("(.+?)\\s*[Ss]eason\\s*(\\d{1,2})\\s*[Ee]pisode\\s*(\\d{1,3})"),
    )
    private val seasonOnly = Regex("(.+?)\\s*(?:[Tt]emp(?:orada)?\\.?|[Ss]eason)\\s*(\\d{1,2})")

    private fun clean(s: String) = s.trim().trimEnd('-', '_', '|', ':', '.', ' ').trim()

    fun parse(name: String): Info? {
        for (p in patterns) {
            val m = p.find(name) ?: continue
            val series = clean(m.groupValues[1])
            if (series.isEmpty()) continue
            return Info(series, m.groupValues[2].toIntOrNull() ?: 1, m.groupValues[3].toIntOrNull())
        }
        seasonOnly.find(name)?.let { m ->
            val series = clean(m.groupValues[1])
            if (series.isNotEmpty()) return Info(series, m.groupValues[2].toIntOrNull() ?: 1, null)
        }
        return null
    }
}

class CatalogRepository(context: Context) {
    private val db = HugoDatabase.get(context)
    private val dao = db.channelDao()

    companion object {
        const val PAGE_SIZE = 100

        @Volatile private var instance: CatalogRepository? = null
        fun init(context: Context) {
            if (instance == null) instance = CatalogRepository(context.applicationContext)
        }
        fun get(context: Context): CatalogRepository =
            instance ?: CatalogRepository(context.applicationContext).also { instance = it }
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
        val endpoint = "${HugoApi.baseUrl}/channels?url=$encoded&limit=80000"
        val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 180000
        if (conn.responseCode !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val apiErr = Regex(""error"\s*:\s*"([^"]+)"").find(err)?.groupValues?.get(1)
            conn.disconnect()
            throw Exception(
                when {
                    apiErr == "timeout" || conn.responseCode == 504 -> "o servidor da lista demorou demais"
                    apiErr == "empty_playlist" -> "lista vazia ou inválida"
                    apiErr?.startsWith("upstream_") == true -> "o provedor recusou a lista (${apiErr.removePrefix("upstream_")})"
                    else -> "HTTP ${conn.responseCode}"
                }
            )
        }
        onProgress("Lendo lista...")
        dao.deleteChannels(playlistId)
        dao.deleteCategories(playlistId)

        val reader = JsonReader(conn.inputStream.bufferedReader())
        reader.isLenient = true
        val channels = ArrayList<ChannelEntity>(800)
        var total = 0
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "channels" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginObject()
                            var name = "Canal"; var url = ""; var logo: String? = null
                            var group = "Outros"; var type = "live"
                            var seriesName: String? = null; var season: Int? = null; var episode: Int? = null
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "name" -> name = reader.nextString()
                                    "url" -> url = reader.nextString()
                                    "logo" -> logo = reader.nextString().takeIf { it.isNotEmpty() }
                                    "group" -> group = reader.nextString()
                                    "type" -> type = reader.nextString()
                                    "seriesName" -> seriesName = reader.nextString().takeIf { it.isNotBlank() }
                                    "season" -> season = reader.nextInt().takeIf { it > 0 }
                                    "episode" -> episode = reader.nextInt().takeIf { it > 0 }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (type == "series" && seriesName.isNullOrBlank()) {
                                val parsed = SeriesParser.parse(name)
                                seriesName = parsed?.seriesName ?: name.trim()
                                season = season ?: parsed?.season ?: 1
                                episode = episode ?: parsed?.episode
                            }
                            channels.add(ChannelEntity(name = name, url = url, logo = logo, group = group, type = type, playlistId = playlistId, seriesName = seriesName, season = season, episode = episode))
                            total++
                            if (channels.size >= 800) {
                                dao.insertChannels(channels.toList())
                                channels.clear()
                                onProgress("Salvando... $total")
                            }
                        }
                        reader.endArray()
                    }
                    "categories" -> {
                        reader.beginArray()
                        val categories = ArrayList<CategoryEntity>()
                        var pos = 0
                        while (reader.hasNext()) {
                            reader.beginObject()
                            var cname = ""; var ccount = 0; var ctype = "live"
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "name" -> cname = reader.nextString()
                                    "count" -> ccount = reader.nextInt()
                                    "type" -> ctype = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            categories.add(CategoryEntity(name = cname, count = ccount, type = ctype, playlistId = playlistId, position = pos++))
                        }
                        reader.endArray()
                        if (categories.isNotEmpty()) dao.insertCategories(categories)
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        } finally {
            reader.close()
            conn.disconnect()
        }
        if (channels.isNotEmpty()) dao.insertChannels(channels)
        onFraction(1f)
        onProgress("Pronto! $total itens")
    }

    /** Apaga do aparelho o catálogo salvo de uma playlist. */
    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        dao.deleteChannels(playlistId)
        dao.deleteCategories(playlistId)
    }

    suspend fun getChannels(playlistId: String, type: String, group: String? = null, page: Int = 0, pageSize: Int = PAGE_SIZE) =
        withContext(Dispatchers.IO) {
            if (group != null) dao.getByTypeAndGroup(playlistId, type, group, pageSize, page * pageSize)
            else dao.getByType(playlistId, type, pageSize, page * pageSize)
        }

    /** Página para as abas (categoria e busca opcionais). Ao vivo mantém a ordem original. */
    suspend fun page(playlistId: String, type: String, group: String?, query: String?, page: Int, pageSize: Int = PAGE_SIZE): List<ChannelEntity> =
        withContext(Dispatchers.IO) {
            dao.page(playlistId, type, group, query?.trim()?.takeIf { it.isNotEmpty() }, type == "live", pageSize, page * pageSize)
        }

    suspend fun countFiltered(playlistId: String, type: String, group: String?, query: String?): Int =
        withContext(Dispatchers.IO) { dao.countFiltered(playlistId, type, group, query?.trim()?.takeIf { it.isNotEmpty() }) }

    suspend fun seriesPage(playlistId: String, group: String?, query: String?, page: Int, pageSize: Int = PAGE_SIZE): List<SeriesSummary> =
        withContext(Dispatchers.IO) { dao.seriesPage(playlistId, group, query?.trim()?.takeIf { it.isNotEmpty() }, pageSize, page * pageSize) }

    suspend fun seriesCount(playlistId: String, group: String?, query: String?): Int =
        withContext(Dispatchers.IO) { dao.seriesCount(playlistId, group, query?.trim()?.takeIf { it.isNotEmpty() }) }

    suspend fun episodes(playlistId: String, seriesName: String): List<ChannelEntity> =
        withContext(Dispatchers.IO) { dao.episodes(playlistId, seriesName) }

    suspend fun getCategories(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.getCategories(playlistId, type) }

    suspend fun count(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.countByType(playlistId, type) }
}
