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
import android.util.JsonToken
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

private fun JsonReader.nextStringOrNull(): String? {
    return if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
}
private fun JsonReader.nextIntOrNull(): Int? {
    return if (peek() == JsonToken.NULL) { nextNull(); null } else nextInt()
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

    /** Limite de lista baixada no aparelho (só fallback 403). Evita OOM. */
    private val maxClientM3uBytes = 40L * 1024 * 1024

    /**
     * Fallback: provedor bloqueou o IP do VPS (403).
     * Baixa a M3U no aparelho e manda o texto ao backend só para classificar.
     * NÃO é o caminho padrão — só quando o servidor devolve upstream_403.
     */
    private fun downloadM3uOnDevice(playlistUrl: String): String {
        val conn = URL(playlistUrl).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
        conn.setRequestProperty("Accept", "*/*")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw Exception("provedor recusou no aparelho ($code)")
            }
            val len = conn.contentLengthLong
            if (len > maxClientM3uBytes) {
                throw Exception("lista grande demais para baixar no aparelho (${len / 1_000_000} MB)")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > maxClientM3uBytes) {
                throw Exception("lista grande demais para baixar no aparelho")
            }
            val text = bytes.toString(Charsets.UTF_8)
            if (!text.contains("#EXTINF")) {
                throw Exception("lista inválida (sem canais)")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun postM3uToServer(m3u: String): java.io.InputStream {
        val endpoint = "${HugoApi.baseUrl}/channels/from-m3u?limit=80000"
        val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        try {
            // Write in small chunks without holding full byte array
            conn.outputStream.use { os ->
                val writer = os.bufferedWriter(Charsets.UTF_8)
                var i = 0
                val step = 32 * 1024
                while (i < m3u.length) {
                    val end = minOf(i + step, m3u.length)
                    writer.write(m3u, i, end - i)
                    i = end
                }
                writer.flush()
            }
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw Exception("servidor não processou a lista (${conn.responseCode}) $err")
            }
            val responseBytes = conn.inputStream.use { it.readBytes() }
            return responseBytes.inputStream()
        } finally {
            conn.disconnect()
        }
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

        var input: java.io.InputStream
        if (conn.responseCode in 200..299) {
            input = conn.inputStream
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val apiErr = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(err)?.groupValues?.get(1)
            val code = conn.responseCode
            conn.disconnect()
            val is403 = apiErr == "upstream_403" || code == 403 ||
                (apiErr?.startsWith("upstream_") == true && apiErr.contains("403"))
            if (!is403) {
                throw Exception(
                    when {
                        apiErr == "timeout" || code == 504 -> "o servidor da lista demorou demais"
                        apiErr == "empty_playlist" -> "lista vazia ou inválida"
                        apiErr?.startsWith("upstream_") == true ->
                            "o provedor recusou a lista (${apiErr.removePrefix("upstream_")})"
                        else -> "HTTP $code"
                    }
                )
            }
            onProgress("Provedor bloqueou o servidor. Baixando no aparelho...")
            val m3u = downloadM3uOnDevice(playlistUrl)
            onProgress("Organizando lista...")
            input = postM3uToServer(m3u)
        }

        onProgress("Lendo lista...")
        dao.deleteChannels(playlistId)
        dao.deleteCategories(playlistId)

        val reader = JsonReader(input.bufferedReader())
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
                            var name = "Canal"
                            var url = ""
                            var logo: String? = null
                            var group = "Outros"
                            var type = "live"
                            var seriesName: String? = null
                            var season: Int? = null
                            var episode: Int? = null
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "name" -> name = reader.nextStringOrNull() ?: "Canal"
                                    "url" -> url = reader.nextStringOrNull() ?: ""
                                    "logo" -> logo = reader.nextStringOrNull()
                                    "group" -> group = reader.nextStringOrNull() ?: "Outros"
                                    "type" -> type = reader.nextStringOrNull() ?: "live"
                                    "seriesName" -> seriesName = reader.nextStringOrNull()
                                    "season" -> season = reader.nextIntOrNull()
                                    "episode" -> episode = reader.nextIntOrNull()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (url.isBlank()) continue
                            if (seriesName == null && type == "series") {
                                SeriesParser.parse(name)?.let {
                                    seriesName = it.seriesName
                                    season = season ?: it.season
                                    episode = episode ?: it.episode
                                }
                            }
                            channels.add(
                                ChannelEntity(
                                    id = 0L,
                                    name = name,
                                    url = url,
                                    logo = logo,
                                    group = group,
                                    type = type,
                                    playlistId = playlistId,
                                    seriesName = seriesName,
                                    season = season,
                                    episode = episode,
                                )
                            )
                            total++
                            if (channels.size >= 800) {
                                dao.insertChannels(channels.toList())
                                channels.clear()
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
                            var cname = "Outros"
                            var ccount = 0
                            var ctype = "live"
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "name" -> cname = reader.nextString()
                                    "count" -> ccount = reader.nextInt()
                                    "type" -> ctype = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            categories.add(
                                CategoryEntity(
                                    name = cname,
                                    count = ccount,
                                    type = ctype,
                                    playlistId = playlistId,
                                    position = pos++,
                                )
                            )
                        }
                        reader.endArray()
                        if (categories.isNotEmpty()) dao.insertCategories(categories)
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        } finally {
            try { reader.close() } catch (_: Exception) {}
            try { conn.disconnect() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
        }
        if (channels.isNotEmpty()) dao.insertChannels(channels)
        onFraction(1f)
        onProgress("Pronto! $total itens")
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        dao.deleteChannels(playlistId)
        dao.deleteCategories(playlistId)
    }

    suspend fun getChannels(playlistId: String, type: String, group: String? = null, page: Int = 0, pageSize: Int = PAGE_SIZE) =
        withContext(Dispatchers.IO) {
            if (group != null) dao.getByTypeAndGroup(playlistId, type, group, pageSize, page * pageSize)
            else dao.getByType(playlistId, type, pageSize, page * pageSize)
        }

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
