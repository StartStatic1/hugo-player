package com.hugo.player.data

import com.hugo.player.data.db.SeriesTwinEntity
import android.content.Context
import com.hugo.player.data.db.CategoryEntity
import com.hugo.player.data.db.ChannelEntity
import com.hugo.player.data.db.HugoDatabase
import com.hugo.player.data.db.SeriesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
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
        // compiladas 1x (antes eram 3 Regex novas por linha: ~240 mil numa lista grande, deixava "Organizando" lento)
        private val RX_GROUP = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        private val RX_LOGO = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        private val RX_TVGID = Regex("tvg-id=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        /** valor que nunca é nome de categoria (a lista NOT IN nunca fica vazia) */
        private const val NO_GROUP = "\u0001__hugo_none__"

        @Volatile private var instance: CatalogRepository? = null
        fun init(context: Context) {
            if (instance == null) instance = CatalogRepository(context.applicationContext)
        }
        fun get(context: Context): CatalogRepository =
            instance ?: CatalogRepository(context.applicationContext).also { instance = it }
        fun clearAll() {}

        /** Tamanho real da lista guardada no aparelho (banco hugo.db + arquivos auxiliares do SQLite). */
        fun storageBytes(context: Context): Long {
            val base = context.applicationContext.getDatabasePath(HugoDatabase.FILE_NAME)
            return listOf("", "-wal", "-shm", "-journal").sumOf { java.io.File(base.path + it).takeIf { f -> f.exists() }?.length() ?: 0L }
        }
    }

    /** "Limpar" das Configurações: apaga a lista guardada (canais, filmes, séries); baixa de novo ao abrir a playlist. */
    suspend fun clearLocalCatalog() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        genPrefs.edit().clear().commit()
        twinsReady.clear()
        dedupReady.clear()
    }

    /**
     * Atualizar uma lista que já está no aparelho: a nova é gravada com outra chave ("id\u0001g<hora>") e,
     * no fim, só troca o ponteiro (prefs) para ela — instantâneo, mesmo com 400 mil itens. A antiga é apagada
     * depois, aos poucos, em segundo plano. (0.5.62 regravava todas as linhas numa transação só: numa lista enorme
     * a TV ficava minutos parada em "Pronto!".) Se cair no meio, a lista antiga continua inteira.
     */
    private val genPrefs = context.applicationContext.getSharedPreferences("hugo_catalog_gen", Context.MODE_PRIVATE)
    /** chave real das linhas da lista no banco */
    private fun k(playlistId: String): String = genPrefs.getString(playlistId, null) ?: playlistId
    private fun realId(key: String) = key.substringBefore('\u0001')
    private fun List<ChannelEntity>.r(): List<ChannelEntity> = map { if ('\u0001' in it.playlistId) it.copy(playlistId = realId(it.playlistId)) else it }
    private fun List<CategoryEntity>.rc(): List<CategoryEntity> = map { if ('\u0001' in it.playlistId) it.copy(playlistId = realId(it.playlistId)) else it }

    /** apaga as linhas de uma chave em pedaços pequenos (não trava o banco nem a tela) */
    private fun deleteKeyChunked(key: String) {
        val w = db.openHelper.writableDatabase
        listOf("channels", "categories", "series_twins").forEach { t ->
            while (true) {
                val n = w.compileStatement("DELETE FROM $t WHERE rowid IN (SELECT rowid FROM $t WHERE playlistId = ? LIMIT 5000)")
                    .use { it.bindString(1, key); it.executeUpdateDelete() }
                if (n <= 0) break
            }
        }
    }

    /** sobras (lista antiga já trocada ou atualização interrompida) desta playlist, em segundo plano */
    private fun cleanupOld(playlistId: String, keep: Set<String>) {
        cleanupScope.launch {
            cleanupLock.withLock {
                runCatching {
                    val current = k(playlistId)
                    dao.playlistKeys().filter { realId(it) == playlistId && it != current && it !in keep }
                        .forEach { deleteKeyChunked(it) }
                }.onFailure { android.util.Log.w("Hugo", "limpeza da lista antiga falhou", it) }
            }
        }
    }
    private val cleanupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val cleanupLock = kotlinx.coroutines.sync.Mutex()

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

    /** itens salvos por vez no banco (cada lote = 1 transação do Room) */
    private val BATCH = 5000

    suspend fun syncPlaylist(
        playlistId: String,
        playlistUrl: String,
        onProgress: (String) -> Unit = {},
        onFraction: (Float?) -> Unit = {},
        /** chamado uma vez, assim que o 1º lote de itens está salvo (dá para abrir a lista já) */
        onFirstBatch: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        onProgress("Conectando ao servidor...")
        onFraction(null)
        val cur = k(playlistId)
        val fresh = dao.hasChannels(cur)
        val target = if (fresh) playlistId + "\u0001g" + System.currentTimeMillis() else cur
        try {
            syncInto(playlistId, target, playlistUrl, onProgress, onFraction, onFirstBatch)
            if (target != cur) {
                onProgress("Finalizando...")
                // troca instantânea: só o ponteiro (commit síncrono antes de seguir)
                genPrefs.edit().putString(playlistId, target).commit()
                cleanupOld(playlistId, setOf(target))
            }
        } catch (e: Throwable) {
            if (target != cur) cleanupOld(playlistId, emptySet())
            if (e is OutOfMemoryError) throw Exception("pouca memória no aparelho para organizar esta lista")
            if (e is android.database.sqlite.SQLiteFullException) throw Exception("pouco espaço livre no aparelho para guardar esta lista")
            throw e
        }
        invalidateSeriesTwins(playlistId)
        onProgress("Juntando repetidos..."); ensureDedup(playlistId)
        onFraction(1f)
    }

    private suspend fun syncInto(
        playlistId: String,
        target: String,
        playlistUrl: String,
        onProgress: (String) -> Unit,
        onFraction: (Float?) -> Unit,
        onFirstBatch: () -> Unit,
    ) {

        // Xtream via VPS first (more organized). Local download only on 403.
        // só o id da playlist + aparelho: o servidor usa o link guardado (sem usuário/senha na URL)
        val identity = DeviceIdentity.get(appContext)
        val endpoint = "${HugoApi.baseUrl}/channels?playlistId=${HugoApi.enc(playlistId)}&limit=80000"
        val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("x-device-id", identity.deviceId)
        conn.setRequestProperty("x-device-key", identity.deviceKey)
        conn.connectTimeout = 30000
        conn.readTimeout = 180000

        try {
            val code = conn.responseCode
            if (code in 200..299) {
                onProgress("Lendo lista do servidor...")
                onFraction(0.05f)
                dao.deleteChannels(target); dao.deleteCategories(target)
                if ('\u0001' !in target) invalidateSeriesTwins(playlistId)
                // total real (servidor novo manda no cabeçalho e antes da lista no JSON)
                val headerTotal = conn.getHeaderField("X-Hugo-Total")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                val total = parseServerJson(conn.inputStream, target, onProgress, onFraction, headerTotal, onFirstBatch, accountId = playlistId)
                onProgress("Pronto! $total itens")
                return
            }

            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val apiErr = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(err)?.groupValues?.get(1)
            val is403 = apiErr == "upstream_403" || code == 403 ||
                (apiErr?.startsWith("upstream_") == true && apiErr.contains("403"))

            // servidor não achou a playlist deste aparelho: baixa direto no aparelho (sem mandar o link ao servidor)
            val notOnServer = apiErr == "playlist_not_found" || apiErr == "invalid_credentials" || apiErr == "playlist_not_registered"
            if (!is403 && !notOnServer && apiErr != "empty_playlist" && code !in listOf(502, 504)) {
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

        onProgress("O provedor bloqueou nosso servidor. Baixando a lista direto no aparelho — pode demorar mais...")
        val total = importM3uFullyLocal(target, playlistUrl, onProgress, onFraction)
        saveAccountLocal(playlistId, playlistUrl)
        onProgress("Pronto! $total itens")
    }

    private suspend fun parseServerJson(
        input: java.io.InputStream,
        playlistId: String,
        onProgress: (String) -> Unit,
        onFraction: (Float?) -> Unit,
        knownTotal: Int? = null,
        onFirstBatch: () -> Unit = {},
        accountId: String = playlistId,
    ): Int {
        val reader = JsonReader(input.bufferedReader())
        reader.isLenient = true
        val channels = ArrayList<ChannelEntity>(BATCH)
        var total = 0
        var realTotal: Int? = knownTotal
        var firstBatchDone = false
        var estimated = knownTotal ?: 50000
        var account: Pair<Long?, Boolean>? = null
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "total" -> {
                        val t = reader.nextIntOrNull()
                        if (realTotal == null && t != null && t > 0) { realTotal = t; estimated = t }
                    }
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
                                val rt = realTotal
                                if (rt != null) {
                                    val f = (total.toFloat() / rt).coerceIn(0.05f, 0.99f)
                                    onProgress("Carregando lista... ${(f * 100).toInt()}% ($total de $rt itens)")
                                    onFraction(f)
                                } else {
                                    val pct = ((total.toFloat() / estimated).coerceIn(0.05f, 0.95f) * 100).toInt()
                                    onProgress("Lendo lista... $pct% ($total itens)")
                                    onFraction((total.toFloat() / estimated).coerceIn(0.05f, 0.95f))
                                }
                            }
                            if (channels.size >= BATCH) {
                                dao.insertChannels(channels.toList())
                                channels.clear()
                                if (!firstBatchDone) { firstBatchDone = true; runCatching { onFirstBatch() } }
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
                    // vencimento da conta Xtream (null quando a lista veio por M3U)
                    "account" -> {
                        if (reader.peek() == android.util.JsonToken.NULL) reader.nextNull()
                        else {
                            var expIso: String? = null
                            var unlimited = false
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "expiresAt" -> expIso = reader.nextStringOrNull()
                                    "unlimited" -> unlimited = if (reader.peek() == android.util.JsonToken.BOOLEAN) reader.nextBoolean() else { reader.skipValue(); false }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            account = ListExpiry.parseIso(expIso) to unlimited
                        }
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
        if (!firstBatchDone && total > 0) runCatching { onFirstBatch() }
        // sem "account" (M3U ou servidor antigo): não marca nada; a conferência leve (ListExpiry.refreshIfStale) resolve
        account?.let { a -> ListExpiry.save(appContext, accountId, a.first, a.second) }
        return total
    }

    /** Lista baixada no aparelho: se for Xtream, pergunta o vencimento direto ao provedor (rápido; falhar não importa). */
    private suspend fun saveAccountLocal(playlistId: String, playlistUrl: String) {
        val u = android.net.Uri.parse(playlistUrl)
        if (u.getQueryParameter("username") == null || u.getQueryParameter("password") == null) ListExpiry.markNotXtream(appContext, playlistId)
        else ListExpiry.fromProvider(appContext, playlistId, playlistUrl)
    }

    class NotAListException : Exception("o provedor respondeu uma página em vez da lista")

    private fun looksLikeM3u(f: java.io.File): Boolean {
        val head = f.inputStream().use { val b = ByteArray(2048); val n = it.read(b); if (n <= 0) "" else String(b, 0, n, Charsets.UTF_8) }
        val t = head.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return t.startsWith("#EXTM3U", ignoreCase = true) || t.contains("#EXTINF", ignoreCase = true)
    }

    private fun relayWorthy(e: Throwable): Boolean =
        e is NotAListException || com.hugo.player.net.SecureNet.isDnsBlock(e) || e is java.io.IOException ||
            e.message == com.hugo.player.net.SecureNet.BLOCKED_MSG ||
            Regex("\\((403|451|5\\d\\d)\\)").containsMatchIn(e.message.orEmpty())

    data class Relay(val url: String, val key: String)

    /** Rotas alternativas da lista (Cloudflare Worker), entregues pelo nosso servidor só para aparelhos autenticados. */
    private fun listRelays(): List<Relay> {
        val id = DeviceIdentity.get(appContext)
        val c = URL("${HugoApi.baseUrl}/channels/relay").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("x-device-id", id.deviceId); c.setRequestProperty("x-device-key", id.deviceKey)
        try {
            if (c.responseCode !in 200..299) return emptyList()
            val arr = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("relays") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o -> Relay(o.optString("url"), o.optString("key")).takeIf { it.url.startsWith("https://") && it.key.isNotEmpty() } } }
        } finally { c.disconnect() }
    }

    private fun relayRequest(r: Relay, target: String): okhttp3.Request =
        okhttp3.Request.Builder().url(r.url)
            .header("x-hugo-relay", r.key)
            // o link (com usuário/senha) vai num cabeçalho, nunca na URL
            .header("x-hugo-target", android.util.Base64.encodeToString(target.toByteArray(), android.util.Base64.NO_WRAP))
            .header("x-hugo-ua", "VLC/3.0.20 LibVLC/3.0.20")
            .header("Accept-Encoding", "gzip")
            .build()

    private suspend fun importM3uFullyLocal(
        playlistId: String,
        playlistUrl: String,
        onProgress: (String) -> Unit,
        onFraction: (Float?) -> Unit,
    ): Int {
        val tmp = java.io.File.createTempFile("hugo_m3u_", ".m3u")
        try {
            fun download(req: okhttp3.Request) {
                // DNS seguro (SecureNet): se a operadora bloquear o endereço, resolve pelo Cloudflare/Google
                val call = com.hugo.player.net.SecureNet.client.newBuilder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(req)
                val resp = try { call.execute() } catch (e: Exception) {
                    if (com.hugo.player.net.SecureNet.isDnsBlock(e)) throw Exception(com.hugo.player.net.SecureNet.BLOCKED_MSG)
                    throw e
                }
                val conn = object {
                    val contentLengthLong = resp.body?.contentLength() ?: -1L
                    val contentEncoding: String? = resp.header("Content-Encoding")
                    val inputStream: java.io.InputStream get() = resp.body!!.byteStream()
                    fun disconnect() { resp.close() }
                }
                try {
                    val code = resp.code
                    if (code !in 200..299) throw Exception("provedor recusou no aparelho ($code)")
                    val len = conn.contentLengthLong
                    if (len > maxClientM3uBytes && conn.contentEncoding.isNullOrBlank()) throw Exception("lista muito grande (${len / 1_000_000} MB)")
                    var written = 0L
                    onProgress("Baixando... 0%")
                    onFraction(0f)
                    var wire = 0L
                    val raw = object : java.io.FilterInputStream(conn.inputStream) {
                        override fun read(): Int = super.read().also { if (it >= 0) wire++ }
                        override fun read(b: ByteArray, off: Int, l: Int): Int = super.read(b, off, l).also { if (it > 0) wire += it }
                    }
                    val gz = conn.contentEncoding?.contains("gzip", ignoreCase = true) == true
                    (if (gz) java.util.zip.GZIPInputStream(raw, 64 * 1024) else raw).use { input ->
                        tmp.outputStream().buffered().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                written += n
                                if (written > maxClientM3uBytes) throw Exception("lista muito grande para o aparelho")
                                out.write(buf, 0, n)
                                if (len > 0 && written % (512 * 1024) < 64 * 1024) {
                                    val pct = ((wire.toFloat() / len) * 100).toInt().coerceIn(0, 99)
                                    onProgress("Baixando... $pct%")
                                    onFraction((wire.toFloat() / len).coerceIn(0f, 0.85f))
                                } else if (len <= 0 && written % (2 * 1024 * 1024) < 64 * 1024) {
                                    onProgress("Baixando... ${wire / 1_000_000} MB" + if (gz) " (compactado)" else "")
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                // tem que ser lista de verdade (provedor que bloqueia costuma mandar a página "Welcome to nginx")
                if (!looksLikeM3u(tmp)) throw NotAListException()
            }
            fun direct() = okhttp3.Request.Builder().url(playlistUrl)
                .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                .header("Accept", "*/*")
                // pede compactado e descompacta aqui (assim o contador mostra o que passa de verdade pela internet)
                .header("Accept-Encoding", "gzip")
                .build()
            try {
                download(direct())
            } catch (e: Exception) {
                // bloqueou no aparelho (DNS, tempo, conexão, página de bloqueio): tenta a rota alternativa só da lista
                if (!relayWorthy(e)) throw e
                val relays = runCatching { listRelays() }.getOrDefault(emptyList())
                if (relays.isEmpty()) throw e
                onProgress("O provedor bloqueou esta internet. Baixando a lista pela rota alternativa...")
                var ok = false
                for (r in relays) {
                    try { download(relayRequest(r, playlistUrl)); ok = true; break }
                    catch (re: Exception) { android.util.Log.w("Hugo", "rota alternativa falhou: ${re.javaClass.simpleName}") }
                }
                if (!ok) throw if (e is NotAListException) Exception("o provedor bloqueou esta internet (respondeu uma página em vez da lista)") else e
            }
            // o banco ocupa ~1,5x o arquivo da lista: sem espaço o SQLite falha no meio
            val free = appContext.getDatabasePath(HugoDatabase.FILE_NAME).parentFile?.usableSpace ?: Long.MAX_VALUE
            if (free < tmp.length() * 3 / 2 + 30_000_000L) throw Exception("pouco espaço livre no aparelho para guardar esta lista (${free / 1_000_000} MB livres)")
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
        dao.deleteChannels(playlistId); dao.deleteCategories(playlistId)
        if ('\u0001' !in playlistId) invalidateSeriesTwins(playlistId)
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
                    pendingGroup = RX_GROUP.find(t)?.groupValues?.get(1)?.trim()?.ifBlank { "Outros" } ?: "Outros"
                    pendingLogo = RX_LOGO.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                    pendingTvgId = RX_TVGID.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
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
        val key = k(playlistId)
        dao.deleteChannels(key); dao.deleteCategories(key); invalidateSeriesTwins(playlistId)
        dao.deleteChannels(playlistId); dao.deleteCategories(playlistId)
        genPrefs.edit().remove(playlistId).commit()
        cleanupOld(playlistId, emptySet())
    }

    suspend fun getChannels(playlistId: String, type: String, group: String? = null, page: Int = 0, pageSize: Int = PAGE_SIZE) =
        withContext(Dispatchers.IO) {
            if (group != null) dao.getByTypeAndGroup(k(playlistId), type, group, pageSize, page * pageSize).r()
            else dao.getByType(k(playlistId), type, pageSize, page * pageSize).r()
        }

    suspend fun page(playlistId: String, type: String, group: String?, query: String?, page: Int, pageSize: Int = PAGE_SIZE): List<ChannelEntity> =
        withContext(Dispatchers.IO) {
            // repetidos (cópias do filme / qualidades do canal) somem e viram opção no item principal
            ensureDedup(playlistId)
            dao.page(k(playlistId), type, group, query?.trim()?.takeIf { it.isNotEmpty() }, type == "live", pageSize, page * pageSize, dedupeFor(type), hiddenGroups(playlistId, type, group)).r()
        }

    /** Categorias adultas escondidas de "Todos"/busca enquanto o bloqueio por PIN está ativo. */
    private val appContext = context.applicationContext
    private suspend fun hiddenGroups(playlistId: String, type: String, group: String?): List<String> {
        val none = listOf(NO_GROUP)
        if (group != null || !AdultLock.isLocked(appContext)) return none
        return none + dao.getCategories(k(playlistId), type).rc().map { it.name }.filter { AdultLock.isAdultCategory(it) }
    }

    suspend fun countFiltered(playlistId: String, type: String, group: String?, query: String?): Int =
        withContext(Dispatchers.IO) { ensureDedup(playlistId); dao.countFiltered(k(playlistId), type, group, query?.trim()?.takeIf { it.isNotEmpty() }, dedupeFor(type), hiddenGroups(playlistId, type, group)) }

    /** Filmes contando as cópias (dublado/legendado/4K/repetido) como um só. */
    suspend fun mergedMovieCount(playlistId: String): Int = countFiltered(playlistId, "movie", null, null)

    /** Canais ao vivo contando as qualidades do mesmo canal como um só (quando agrupado). */
    suspend fun mergedLiveCount(playlistId: String): Int = countFiltered(playlistId, "live", null, null)

    /** Para cada filme dublado da lista, a versão legendada exata ("Nome [L] (Ano)"), se existir. Chave = id do dublado. */
    suspend fun legendadoTwins(playlistId: String, movies: List<ChannelEntity>): Map<Long, ChannelEntity> = withContext(Dispatchers.IO) {
        val wanted = HashMap<String, Long>()
        movies.filter { it.type == "movie" && !MovieTwins.isLegendado(it.name) }.forEach { m ->
            MovieTwins.legendadoCandidates(m.name).forEach { wanted.putIfAbsent(it, m.id) }
        }
        if (wanted.isEmpty()) return@withContext emptyMap()
        val out = HashMap<Long, ChannelEntity>()
        wanted.keys.toList().chunked(400).forEach { chunk ->
            dao.byNames(k(playlistId), "movie", chunk).r().forEach { l -> wanted[l.name]?.let { out.putIfAbsent(it, l) } }
        }
        out
    }

    // pares dublada/legendada calculados 1x por playlist (refeito após sincronizar)
    private val twinsReady = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val twinsMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureSeriesTwins(playlistId: String) = withContext(Dispatchers.IO) {
        if (twinsReady[playlistId] == true) return@withContext
        twinsMutex.withLock {
            if (twinsReady[playlistId] == true) return@withLock
            runCatching {
                val pairs = SeriesTwins.pairs(dao.seriesNames(k(playlistId)))
                dao.deleteSeriesTwins(k(playlistId))
                if (pairs.isNotEmpty()) pairs.entries.chunked(500).forEach { ch -> dao.insertSeriesTwins(ch.map { SeriesTwinEntity(k(playlistId), it.key, it.value) }) }
            }
            twinsReady[playlistId] = true
        }
    }

    fun invalidateSeriesTwins(playlistId: String) {
        twinsReady.remove(playlistId)
        // a lista mudou: os repetidos são recalculados (as cópias novas do provedor entram no grupo certo)
        dedupReady.remove(playlistId)
        AppSettings.setDedupReady(playlistId, false)
    }

    // repetidos (qualidades do mesmo canal / cópias do mesmo filme): calculado 1x por lista baixada e guardado no banco
    private val dedupReady = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val dedupMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureDedup(playlistId: String) = withContext(Dispatchers.IO) {
        if (dedupReady[playlistId] == true) return@withContext
        dedupMutex.withLock {
            if (dedupReady[playlistId] == true) return@withLock
            if (AppSettings.isDedupReady(playlistId)) { dedupReady[playlistId] = true; return@withLock }
            runCatching {
                val rows = dao.dedupSources(k(playlistId)).map { DedupRow(it.id, it.name, it.group, it.type, it.epgId) }
                val marks = ContentDedup.compute(rows) { AdultLock.isAdultCategory(it) }
                db.runInTransaction {
                    val w = db.openHelper.writableDatabase
                    w.compileStatement("UPDATE channels SET dupOf = NULL, dupOfAll = NULL, variantTags = NULL, variantTagsAll = NULL WHERE playlistId = ?").use { st ->
                        st.bindString(1, k(playlistId)); st.executeUpdateDelete()
                    }
                    w.compileStatement("UPDATE channels SET dupOf = ?, dupOfAll = ?, variantTags = ?, variantTagsAll = ? WHERE id = ?").use { st ->
                        marks.forEach { (id, m) ->
                            st.clearBindings()
                            m.dupOf?.let { st.bindLong(1, it) } ?: st.bindNull(1)
                            m.dupOfAll?.let { st.bindLong(2, it) } ?: st.bindNull(2)
                            m.tags?.let { st.bindString(3, it) } ?: st.bindNull(3)
                            m.tagsAll?.let { st.bindString(4, it) } ?: st.bindNull(4)
                            st.bindLong(5, id)
                            st.executeUpdateDelete()
                        }
                    }
                }
                AppSettings.setDedupReady(playlistId, true)
            }.onFailure { android.util.Log.w("Hugo", "dedup falhou", it) }
            dedupReady[playlistId] = true
        }
    }

    /** Ao vivo agrupa qualidades? (Configurações > Player) */
    private fun liveGrouped() = AppSettings.groupLiveQualities
    private fun dedupeFor(type: String) = type == "movie" || (type == "live" && liveGrouped())

    /**
     * Todas as versões de um item visível (ordem da lista), no mesmo escopo da tela:
     * categoria aberta ([group] != null) usa dupOf; "Todos" usa dupOfAll.
     */
    suspend fun variantsOf(item: ChannelEntity, group: String?): List<ChannelEntity> = withContext(Dispatchers.IO) {
        ensureDedup(item.playlistId)
        val all = group == null
        runCatching { dao.variantsOf(k(item.playlistId), item.type, item.id, all).r() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(item)
    }

    /** Para favoritos/recentes: o item visível em "Todos" de cada canal (a cópia vira o principal do grupo). */
    suspend fun liveRepresentatives(list: List<ChannelEntity>): List<ChannelEntity> = withContext(Dispatchers.IO) {
        if (!liveGrouped() || list.isEmpty()) return@withContext list
        list.firstOrNull()?.let { ensureDedup(it.playlistId) }
        val fresh = list.map { it.id }.chunked(500).flatMap { dao.byIds(it).r() }.associateBy { it.id }
        val repIds = list.mapNotNull { (fresh[it.id] ?: it).dupOfAll }.distinct()
        val reps = repIds.chunked(500).flatMap { dao.byIds(it).r() }.associateBy { it.id }
        val seen = HashSet<Long>()
        list.mapNotNull { c ->
            val f = fresh[c.id] ?: c
            val r = f.dupOfAll?.let { reps[it] } ?: f
            if (seen.add(r.id)) r else null
        }
    }

    /** Versão dublada/legendada da mesma série (null = só existe uma). */
    suspend fun seriesTwinOf(playlistId: String, name: String): SeriesTwinEntity? {
        ensureSeriesTwins(playlistId)
        return withContext(Dispatchers.IO) { dao.seriesTwinOf(k(playlistId), name) }
    }

    /** dublada -> legendada para os nomes da página. */
    suspend fun seriesTwinsForDubs(playlistId: String, names: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (names.isEmpty()) emptyMap() else dao.seriesTwinsForDubs(k(playlistId), names.take(900)).associate { it.dubName to it.legName }
    }

    suspend fun seriesPage(playlistId: String, group: String?, query: String?, page: Int, pageSize: Int = PAGE_SIZE): List<SeriesSummary> =
        withContext(Dispatchers.IO) { ensureSeriesTwins(playlistId); dao.seriesPage(k(playlistId), group, query?.trim()?.takeIf { it.isNotEmpty() }, pageSize, page * pageSize, hiddenGroups(playlistId, "series", group)) }

    suspend fun seriesCount(playlistId: String, group: String?, query: String?): Int =
        withContext(Dispatchers.IO) { ensureSeriesTwins(playlistId); dao.seriesCount(k(playlistId), group, query?.trim()?.takeIf { it.isNotEmpty() }, hiddenGroups(playlistId, "series", group)) }

    suspend fun episodes(playlistId: String, seriesName: String): List<ChannelEntity> =
        withContext(Dispatchers.IO) { dao.episodes(k(playlistId), seriesName).r() }

    /** Canais ao vivo pelas URLs, na mesma ordem pedida (favoritos / recentes). */
    suspend fun liveByUrls(playlistId: String, urls: List<String>): List<ChannelEntity> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext emptyList()
        val found = HashMap<String, ChannelEntity>()
        urls.distinct().chunked(500).forEach { chunk -> dao.liveByUrls(k(playlistId), chunk).r().forEach { found.putIfAbsent(it.url, it) } }
        urls.mapNotNull { found[it] }
    }

    /** Itens (qualquer tipo) pelas URLs — favoritos. */
    suspend fun byUrls(playlistId: String, urls: List<String>): List<ChannelEntity> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext emptyList()
        urls.distinct().chunked(500).flatMap { dao.byUrls(k(playlistId), it).r() }
    }

    /** Janela da lista ao vivo (mesma ordem da tela), para zapear no player. */
    suspend fun liveWindow(playlistId: String, group: String?, query: String?, offset: Int, limit: Int): List<ChannelEntity> =
        withContext(Dispatchers.IO) {
            ensureDedup(playlistId)
            // mesma lista da tela: com qualidades agrupadas, zapear pula de canal (não de qualidade)
            dao.page(k(playlistId), "live", group, query?.trim()?.takeIf { it.isNotEmpty() }, true, limit, offset.coerceAtLeast(0), dedupeFor("live"), hiddenGroups(playlistId, "live", group)).r()
        }

    suspend fun liveEpgKeys(playlistId: String) =
        withContext(Dispatchers.IO) { dao.liveEpgKeys(k(playlistId)) }

    suspend fun getCategories(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.getCategories(k(playlistId), type).rc() }

    /** Categorias com a contagem já juntando os repetidos (filmes sempre; ao vivo quando agrupado). */
    suspend fun getCategoriesMerged(playlistId: String, type: String): List<com.hugo.player.data.db.CategoryEntity> = withContext(Dispatchers.IO) {
        val cats = dao.getCategories(k(playlistId), type).rc()
        if (!dedupeFor(type)) return@withContext cats
        ensureDedup(playlistId)
        val counts = runCatching { dao.mergedGroupCounts(k(playlistId), type).associate { it.name to it.count } }.getOrNull() ?: return@withContext cats
        cats.map { c -> counts[c.name]?.let { c.copy(count = it) } ?: c }
    }

    suspend fun count(playlistId: String, type: String) =
        withContext(Dispatchers.IO) { dao.countByType(k(playlistId), type) }
}
