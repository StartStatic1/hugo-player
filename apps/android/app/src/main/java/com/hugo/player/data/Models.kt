package com.hugo.player.data

import org.json.JSONArray
import org.json.JSONObject

/** Modelos do catálogo devolvido por GET /catalog (backend). */

data class CatalogCategory(val id: String, val name: String, val count: Int)

data class LiveChannel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val categoryId: String,
    val epgId: String?,
    val streamId: Long?,
    val catchup: Boolean,
    val userAgent: String?,
)

data class Movie(
    val id: String,
    val name: String,
    val url: String,
    val poster: String?,
    val group: String,
    val categoryId: String,
    val year: Int?,
    val rating: Double?,
)

data class Episode(
    val id: String,
    val name: String,
    val season: Int,
    val episode: Int,
    val url: String,
    val cover: String?,
    val plot: String?,
    val durationSecs: Int?,
)

data class Season(val season: Int, val episodes: List<Episode>)

data class Series(
    val id: String,
    val name: String,
    val cover: String?,
    val group: String,
    val categoryId: String,
    val year: Int?,
    val rating: Double?,
    val plot: String?,
    /** Xtream: temporadas carregadas sob demanda */
    val seriesId: Long?,
    val seasons: List<Season>,
    val episodeCount: Int,
)

data class SeriesDetails(
    val name: String,
    val cover: String?,
    val plot: String?,
    val year: Int?,
    val rating: Double?,
    val seasons: List<Season>,
)

data class Section<T>(val categories: List<CatalogCategory>, val items: List<T>)

data class Catalog(
    val source: String,
    val epgUrl: String?,
    val generatedAt: String,
    val live: Section<LiveChannel>,
    val movies: Section<Movie>,
    val series: Section<Series>,
) {
    val isEmpty get() = live.items.isEmpty() && movies.items.isEmpty() && series.items.isEmpty()
}

// ---------- JSON ----------

private fun JSONObject.optStr(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() }?.toInt() else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() }?.toLong() else null

private inline fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(block(it)) }
    return out
}

private fun parseCategories(arr: JSONArray?) = arr.mapObjects {
    CatalogCategory(it.optString("id"), it.optString("name", "Outros"), it.optInt("count", 0))
}

fun parseEpisode(o: JSONObject) = Episode(
    id = o.optString("id"),
    name = o.optString("name", "Episódio"),
    season = o.optInt("season", 1),
    episode = o.optInt("episode", 0),
    url = o.optString("url"),
    cover = o.optStr("cover"),
    plot = o.optStr("plot"),
    durationSecs = o.optIntOrNull("durationSecs"),
)

fun parseSeasons(arr: JSONArray?) = arr.mapObjects { s ->
    Season(s.optInt("season", 1), s.optJSONArray("episodes").mapObjects(::parseEpisode))
}

fun parseCatalog(json: JSONObject): Catalog {
    val live = json.optJSONObject("live")
    val movies = json.optJSONObject("movies")
    val series = json.optJSONObject("series")
    return Catalog(
        source = json.optString("source", "m3u"),
        epgUrl = json.optStr("epgUrl"),
        generatedAt = json.optString("generatedAt", ""),
        live = Section(
            parseCategories(live?.optJSONArray("categories")),
            live?.optJSONArray("items").mapObjects {
                LiveChannel(
                    id = it.optString("id"),
                    name = it.optString("name", "Canal"),
                    url = it.optString("url"),
                    logo = it.optStr("logo"),
                    group = it.optString("group", "Outros"),
                    categoryId = it.optString("categoryId"),
                    epgId = it.optStr("epgId"),
                    streamId = it.optLongOrNull("streamId"),
                    catchup = it.optBoolean("catchup", false),
                    userAgent = it.optStr("userAgent"),
                )
            }
        ),
        movies = Section(
            parseCategories(movies?.optJSONArray("categories")),
            movies?.optJSONArray("items").mapObjects {
                Movie(
                    id = it.optString("id"),
                    name = it.optString("name", "Filme"),
                    url = it.optString("url"),
                    poster = it.optStr("poster"),
                    group = it.optString("group", "Outros"),
                    categoryId = it.optString("categoryId"),
                    year = it.optIntOrNull("year"),
                    rating = it.optDoubleOrNull("rating"),
                )
            }
        ),
        series = Section(
            parseCategories(series?.optJSONArray("categories")),
            series?.optJSONArray("items").mapObjects {
                Series(
                    id = it.optString("id"),
                    name = it.optString("name", "Série"),
                    cover = it.optStr("cover"),
                    group = it.optString("group", "Outros"),
                    categoryId = it.optString("categoryId"),
                    year = it.optIntOrNull("year"),
                    rating = it.optDoubleOrNull("rating"),
                    plot = it.optStr("plot"),
                    seriesId = it.optLongOrNull("seriesId"),
                    seasons = parseSeasons(it.optJSONArray("seasons")),
                    episodeCount = it.optInt("episodeCount", 0),
                )
            }
        ),
    )
}

fun parseSeriesDetails(o: JSONObject) = SeriesDetails(
    name = o.optString("name", ""),
    cover = o.optStr("cover"),
    plot = o.optStr("plot"),
    year = o.optIntOrNull("year"),
    rating = o.optDoubleOrNull("rating"),
    seasons = parseSeasons(o.optJSONArray("seasons")),
)
