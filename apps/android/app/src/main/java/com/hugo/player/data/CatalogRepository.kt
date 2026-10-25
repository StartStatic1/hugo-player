package com.hugo.player.data

import android.content.Context
import com.hugo.player.data.db.CategoryEntity
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.data.db.HugoDatabase
import com.hugo.player.data.db.SeriesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.JsonReader
import android.util.JsonToken
import java.net.URL

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

private fun JsonReader.nextStringOrNull(): String? =
    if (peek() == JsonToken.NULL) { nextNull(); null } else nextString()
private fun JsonReader.nextIntOrNull(): Int? =
    if (peek() == JsonToken.NULL) { nextNull(); null } else nextInt()

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

    private val maxClientM3uBytes = 150L * 1024 * 1024

    private fun extractExtinfName(extinf: String): String {
        val i = extinf.lastIndexOf(',')
        if (i < 0 || i >= extinf.length - 1) return "Canal"
        return extinf.substring(i + 1).trim().ifBlank { "Canal" }
    }

    private fun classifyType(name: String, group: String, url: String): String {
        val u = url.lowercase()
        val g = group.lowercase()
        val n = name.lowercase()

        when {
            "/series/" in u -> return "series"
            "/movie/" in u || "/movies/" in u -> return "movie"
            "/live/" in u -> return "live"
        }

        if (SeriesParser.parse(name) != null) return "series"

        val seriesKeys = listOf(
            "serie", "series", "seriados", "temporada", "season", "novela", "novelas",
            "dorama", "anime", "animes", "tv shows", "tvshows", "minisserie", "minissérie",
        )
        val movieKeys = listOf(
            "filme", "filmes", "movie", "movies", "vod", "cinema", "lancamento", "lançamento",
            "documentario", "documentário",
        )
        val liveKeys = listOf(
            "canal", "canais", "live", "ao vivo", "aovivo", "noticias", "notícias",
            "esporte", "esportes", "channels", "abertos", "ppv",
        )

        val gNorm = g.removePrefix("s • ").removePrefix("f • ").removePrefix("c • ").trim()
        val gFull = "$g $gNorm"

        if (seriesKeys.any { it in gFull || it in n }) return "series"
        if (g.startsWith("s ") || g.startsWith("s•") || g.startsWith("s •") || g.startsWith("s-")) return "series"
        if (g.startsWith("f ") || g.startsWith("f•") || g.startsWith("f •") || g.startsWith("f-")) return "movie"
        if (g.startsWith("c ") || g.startsWith("c•") || g.startsWith("c •") || g.startsWith("c-")) return "live"

        if (movieKeys.any { it in gFull }) return "movie"
        if (liveKeys.any { it in gFull }) return "live"

        if (".mp4" in u || ".mkv" in u || ".avi" in u || ".m4v" in u) return "movie"
        if (".m3u8" in u || u.endsWith(".ts") || "/ts" in u) return "live"
        return "live"
    }

    suspend fun syncPlaylist(
        playlistId: String,
        playlistUrl: String,
        onProgress: (String) -> Unit = {},
        onFraction: (Float?) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        onProgress("Conectando ao servidor...")
        onFraction(null)

        // Xtream via VPS first (more organized). Local download only on 403.
        val encoded = java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        val endpoint = "${HugoApi.baseUrl}/channels?url=$encoded&limit=80000"
        val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 180000

        try {
            val code = conn.responseCode
            if (code in 200..299) {
                onProgress("Lendo lista do servidor...")
                onFraction(0.05f)
                dao.deleteChannels(playlistId)
                dao.deleteCategories(playlistId)
                val total = parseServerJson(conn.inputStream, playlistId, onProgress, onFraction)
                onFraction(1f)
                onProgress("Pronto! $total itens")
                return@withContext
            }

            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val apiErr = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(err)?.groupValues?.get(1)
            val is403 = apiErr == "upstream_403" || code == 403 ||
                (apiErr?.startsWith("upstream_") == true && apiErr.contains("403"))

            if (!is403 && apiErr != "empty_playlist" && code !in listOf(502, 504)) {
                throw Exception(
                    when {
                        apiErr == "timeout" || code == 504 -> "o servidor da lista demorou demais"
                        apiErr?.startsWith("upstream_") == true ->
                            "o provedor recusou a lista (${apiErr.removePrefix("upstream_")})"
                        else -> "HTTP $code"
                    }
                )
            }
        } catch (e: Exception) {
            if (e.message?.contains("connection abort", ignoreCase = true) == true ||
                e.message?.contains("Software caused", ignoreCase = true) == true ||
                e is java.net.SocketTimeoutException ||
                e is java.io.IOException && e.message?.contains("abort", ignoreCase = true) == true) {
                // connection dropped mid-transfer — fall through to local
            } else throw e
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }

        onProgress("Provedor bloqueou o servidor. Baixando no aparelho...")
        val total = importM3uFullyLocal(playlistId, playlistUrl, onProgress, onFraction)
        onFraction(1f)
        onProgress("Pronto! $total itens")
    }

    private suspend fun parseServerJson(
        input: java.io.InputStream,
        playlistId: String,
        onProgress: (String) -> Unit,
        onFraction: (Float?) -> Unit,
    ): Int {
        val reader = JsonReader(input.bufferedReader())
        reader.isLenient = true
        val channels = ArrayList<ChannelEntity>(800)
        var total = 0
        var estimated = 50000
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
                            var epgId: String? = null
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
                                    "epgId" -> epgId = reader.nextStringOrNull()?.takeIf { it.isNotBlank() }
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
                                    id = 0L, name = name, url = url, logo = logo, group = group,
                                    type = type, playlistId = playlistId,
                                    seriesName = seriesName, season = season, episode = episode,
                                    epgId = if (type == "live") epgId else null,
                                )
                            )
                            total++
                            if (total % 1000 == 0) {
                                val pct = ((total.toFloat() / estimated).coerceIn(0.05f, 0.95f) * 100).toInt()
                                onProgress("Lendo lista... $pct% ($total itens)")
                                onFraction((total.toFloat() / estimated).coerceIn(0.05f, 0.95f))
                            }
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
            try { reader.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
        }
        if (channels.isNotEmpty()) dao.insertChannels(channels)
        return total
    }

    private suspend fun importM3uFullyLocal(
        playlistId: String,
        playlistUrl: String,
        onProgress: (String) -> Unit,
        onFraction: (Float?) -> Unit,
    ): Int {
        val tmp = java.io.File.createTempFile("hugo_m3u_", ".m3u")
        try {
            val conn = URL(playlistUrl).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
            conn.setRequestProperty("Accept", "*/*")
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw Exception("provedor recusou no aparelho ($code)")
                val len = conn.contentLengthLong
                if (len > maxClientM3uBytes) throw Exception("lista muito grande (${len / 1_000_000} MB)")
                var written = 0L
                onProgress("Baixando... 0%")
                onFraction(0f)
                conn.inputStream.use { input ->
                    tmp.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            written += n
                            if (written > maxClientM3uBytes) throw Exception("lista muito grande para o aparelho")
                            out.write(buf, 0, n)
                            if (len > 0 && written % (512 * 1024) < 64 * 1024) {
                                val pct = ((written.toFloat() / len) * 100).toInt().coerceIn(0, 99)
                                onProgress("Baixando... $pct%")
                                onFraction((written.toFloat() / len).coerceIn(0f, 0.85f))
                            } else if (len <= 0 && written % (2 * 1024 * 1024) < 64 * 1024) {
                                onProgress("Baixando... ${written / 1_000_000} MB")
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            onProgress("Organizando... 0%")
            onFraction(0.85f)
            return importM3uFullyLocalStreaming(playlistId, tmp, onProgress, onFraction)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun importM3uFullyLocalStreaming(
        playlistId: String,
        tmp: java.io.File,
        onProgress: (String) -> Unit,
        onFraction: (Float?) -> Unit,
    ): Int {
        dao.deleteChannels(playlistId)
        dao.deleteCategories(playlistId)
        val batch = ArrayList<ChannelEntity>(500)
        val catCount = LinkedHashMap<String, Int>()
        var total = 0
        var pendingName = "Canal"
        var pendingGroup = "Outros"
        var pendingLogo: String? = null
        var pendingTvgId: String? = null
        val fileLen = tmp.length().coerceAtLeast(1L)
        var processedBytes = 0L

        val reader = tmp.bufferedReader(Charsets.UTF_8)
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                processedBytes += (line?.length?.toLong() ?: 0L) + 1L
                val t = line!!.trim()
                if (t.startsWith("#EXTINF", ignoreCase = true)) {
                    pendingName = extractExtinfName(t)
                    pendingGroup = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                        .find(t)?.groupValues?.get(1)?.trim()?.ifBlank { "Outros" } ?: "Outros"
                    pendingLogo = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                        .find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    pendingTvgId = Regex("tvg-id=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                        .find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                } else if (t.isNotEmpty() && !t.startsWith("#")) {
                    val type = classifyType(pendingName, pendingGroup, t)
                    var seriesName: String? = null
                    var season: Int? = null
                    var episode: Int? = null
                    if (type == "series") {
                        SeriesParser.parse(pendingName)?.let {
                            seriesName = it.seriesName
                            season = it.season
                            episode = it.episode
                        }
                        if (seriesName == null) seriesName = pendingName
                    }
                    batch.add(
                        ChannelEntity(
                            id = 0L, name = pendingName, url = t, logo = pendingLogo,
                            group = pendingGroup, type = type, playlistId = playlistId,
                            seriesName = seriesName, season = season, episode = episode,
                            epgId = if (type == "live") pendingTvgId else null,
                        )
                    )
                    val ck = "$type\t$pendingGroup"
                    catCount[ck] = (catCount[ck] ?: 0) + 1
                    total++
                    if (batch.size >= 500) {
                        dao.insertChannels(batch.toList())
                        batch.clear()
                        if (total % 500 == 0) {
                            val fraction = (processedBytes.toFloat() / fileLen).coerceIn(0f, 1f)
                            val pct = (85 + fraction * 14).toInt().coerceIn(85, 99)
                            onProgress("Organizando... $pct% ($total itens)")
                            onFraction(0.85f + fraction * 0.14f)
                        }
                    }
                    pendingName = "Canal"
                    pendingGroup = "Outros"
                    pendingLogo = null
                    pendingTvgId = null
                }
            }
        } finally {
            reader.close()
        }
        if (batch.isNotEmpty()) dao.insertChannels(batch)

        val categories = ArrayList<CategoryEntity>()
        var pos = 0
        for ((ck, count) in catCount) {
            val parts = ck.split("\t", limit = 2)
            val type = parts.getOrElse(0) { "live" }
            val name = parts.getOrElse(1) { "Outros" }
            categories.add(CategoryEntity(name = name, count = count, type = type, playlistId = playlistId, position = pos++))
            if (categories.size >= 200) {
                dao.insertCategories(categories.toList())
                categories.clear()
            }
        }
        if (categories.isNotEmpty()) dao.insertCategories(categories)
        return total
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

    suspend fun liveEpgKeys(playlistId: String) =
        withContext(Dispatchers.IO) { dao.liveEpgKeys(playlistId) }

    suspend fun getCategories(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.getCategories(playlistId, type) }

    suspend fun count(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.countByType(playlistId, type) }
}
