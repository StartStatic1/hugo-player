package com.hugo.player.data

import org.json.JSONObject

/**
 * Carrega o catálogo (live / filmes / séries) da playlist pelo backend.
 * Mantém o catálogo atual em memória para as telas.
 */
object CatalogRepository {
    data class Key(val playlistId: String, val deviceId: String, val deviceKey: String)

    @Volatile var key: Key? = null
        private set
    @Volatile var catalog: Catalog? = null
        private set
    @Volatile var playlistName: String? = null

    private val seriesDetails = HashMap<String, SeriesDetails>()

    fun cached(playlistId: String): Catalog? = catalog.takeIf { key?.playlistId == playlistId }

    suspend fun load(playlistId: String, deviceId: String, deviceKey: String, force: Boolean = false): Catalog {
        val k = Key(playlistId, deviceId, deviceKey)
        if (!force) cached(playlistId)?.let { return it }
        val refresh = if (force) "&refresh=1" else ""
        val text = HugoApi.get("/catalog?playlistId=${HugoApi.enc(playlistId)}$refresh", deviceId, deviceKey, readTimeoutMs = 120_000)
        val parsed = parseCatalog(JSONObject(text))
        set(k, parsed)
        return parsed
    }

    /** Usado pelo cache em disco (Parte E). */
    fun set(k: Key, c: Catalog) {
        if (key?.playlistId != k.playlistId) synchronized(seriesDetails) { seriesDetails.clear() }
        key = k
        catalog = c
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
        val d = parseSeriesDetails(JSONObject(text)).let {
            it.copy(name = it.name.ifBlank { series.name }, cover = it.cover ?: series.cover, plot = it.plot ?: series.plot)
        }
        synchronized(seriesDetails) { seriesDetails[series.id] = d }
        return d
    }
}
