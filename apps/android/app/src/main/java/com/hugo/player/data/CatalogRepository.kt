package com.hugo.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Carrega o catálogo (live / filmes / séries) da playlist pelo backend.
 * Ordem: memória -> disco (dentro da validade) -> rede. Se a rede falhar,
 * usa o cache em disco mesmo vencido (modo offline).
 */
object CatalogRepository {
    data class Key(val playlistId: String, val deviceId: String, val deviceKey: String)

    enum class Origin { MEMORY, DISK, NETWORK, STALE_DISK }

    @Volatile var key: Key? = null
        private set
    @Volatile var catalog: Catalog? = null
        private set
    @Volatile var playlistName: String? = null
    /** De onde veio o catálogo atual e quando foi gerado (para mostrar "atualizado há…"). */
    @Volatile var origin: Origin = Origin.NETWORK
        private set
    @Volatile var savedAt: Long = 0L
        private set

    private val seriesDetails = HashMap<String, SeriesDetails>()

    fun init(context: Context) {
        AppSettings.init(context)
        DiskCache.init(context)
    }

    private fun variant() = AppSettings.liveFormat

    fun hasDiskCache(playlistId: String): Boolean = DiskCache.savedAt(playlistId, variant()) != null

    fun cached(playlistId: String): Catalog? = catalog.takeIf { key?.playlistId == playlistId }

    /** Existe cache em disco válido para abrir direto? */
    fun hasFreshDiskCache(playlistId: String): Boolean {
        val at = DiskCache.savedAt(playlistId, variant()) ?: return false
        return System.currentTimeMillis() - at < AppSettings.catalogTtlHours * 3_600_000L
    }

    suspend fun load(playlistId: String, deviceId: String, deviceKey: String, force: Boolean = false): Catalog {
        val k = Key(playlistId, deviceId, deviceKey)
        if (!force) cached(playlistId)?.let { origin = Origin.MEMORY; return it }

        val disk = withContext(Dispatchers.IO) { DiskCache.read(playlistId, variant()) }
        val ttlMs = AppSettings.catalogTtlHours * 3_600_000L
        if (!force && disk != null && disk.ageMs() < ttlMs) {
            val parsed = withContext(Dispatchers.Default) { parseCatalog(disk.catalog) }
            set(k, parsed, Origin.DISK, disk.savedAt)
            if (playlistName == null) playlistName = disk.playlistName
            return parsed
        }

        return try {
            val refresh = if (force) "&refresh=1" else ""
            val text = HugoApi.get(
                "/catalog?playlistId=${HugoApi.enc(playlistId)}&liveFormat=${variant()}$refresh",
                deviceId, deviceKey, readTimeoutMs = 120_000
            )
            val (json, parsed) = withContext(Dispatchers.Default) {
                val j = JSONObject(text)
                j to parseCatalog(j)
            }
            withContext(Dispatchers.IO) { DiskCache.write(playlistId, variant(), json, playlistName) }
            set(k, parsed, Origin.NETWORK, System.currentTimeMillis())
            parsed
        } catch (e: Exception) {
            // Sem rede / servidor fora: usa o que tiver em disco
            if (disk == null) throw e
            val parsed = withContext(Dispatchers.Default) { parseCatalog(disk.catalog) }
            set(k, parsed, Origin.STALE_DISK, disk.savedAt)
            if (playlistName == null) playlistName = disk.playlistName
            parsed
        }
    }

    fun set(k: Key, c: Catalog, from: Origin = Origin.NETWORK, at: Long = System.currentTimeMillis()) {
        if (key?.playlistId != k.playlistId) synchronized(seriesDetails) { seriesDetails.clear() }
        key = k
        catalog = c
        origin = from
        savedAt = at
    }

    /** Limpa memória e disco (Configurações > Limpar cache). */
    fun clearAll() {
        catalog = null
        key = null
        synchronized(seriesDetails) { seriesDetails.clear() }
        DiskCache.clear()
    }

    /** Temporadas/episódios de uma série (Xtream busca sob demanda; M3U já vem agrupado). */
    suspend fun seriesDetails(series: Series): SeriesDetails {
        if (series.seriesId == null && series.seasons.isNotEmpty()) {
            return SeriesDetails(series.name, series.cover, series.plot, series.year, series.rating, series.seasons)
        }
        synchronized(seriesDetails) { seriesDetails[series.id] }?.let { return it }
        val k = key ?: throw ApiException(400, "Playlist não carregada")
        val text = HugoApi.get(
            "/catalog/series/${HugoApi.enc(series.id)}?playlistId=${HugoApi.enc(k.playlistId)}",
            k.deviceId, k.deviceKey
        )
        val d = withContext(Dispatchers.Default) { parseSeriesDetails(JSONObject(text)) }.let {
            it.copy(name = it.name.ifBlank { series.name }, cover = it.cover ?: series.cover, plot = it.plot ?: series.plot)
        }
        synchronized(seriesDetails) { seriesDetails[series.id] = d }
        return d
    }
}
