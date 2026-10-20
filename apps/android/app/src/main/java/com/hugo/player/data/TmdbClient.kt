package com.hugo.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Chama o proxy TMDB do VPS (/tmdb/*). A chave fica no servidor. */
object TmdbClient {
    data class Info(
        val overview: String?,
        val genres: String?,
        val rating: String?,
        val year: String?,
        val poster: String?,
    )

    data class Similar(
        val title: String,
        val poster: String?,
        val year: String?,
        val id: Int,
    )

    private fun clean(title: String): String =
        title.replace(Regex("\\((\\d{4})\\)|\\b(19|20)\\d{2}\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('-', '|', ':')

    suspend fun searchMovie(title: String): Info? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(clean(title), "UTF-8")
        val body = runCatching { HugoApi.get("/tmdb/search?type=movie&q=$q") }.getOrNull() ?: return@withContext null
        val arr = JSONObject(body).optJSONArray("results") ?: return@withContext null
        if (arr.length() == 0) return@withContext null
        val r = arr.getJSONObject(0)
        val id = r.optInt("id")
        val detail = runCatching { HugoApi.get("/tmdb/details/movie/$id") }.getOrNull()
        val d = detail?.let { JSONObject(it) }
        val genres = d?.optJSONArray("genres")?.let { g ->
            (0 until g.length()).joinToString(", ") { g.getJSONObject(it).optString("name") }
        }
        Info(
            overview = d?.optString("overview")?.takeIf { it.isNotBlank() } ?: r.optString("overview").takeIf { it.isNotBlank() },
            genres = genres?.takeIf { it.isNotBlank() },
            rating = r.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) },
            year = r.optString("release_date").take(4).takeIf { it.length == 4 },
            poster = r.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" },
        )
    }

    suspend fun searchTv(title: String): Info? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(clean(title), "UTF-8")
        val body = runCatching { HugoApi.get("/tmdb/search?type=tv&q=$q") }.getOrNull() ?: return@withContext null
        val arr = JSONObject(body).optJSONArray("results") ?: return@withContext null
        if (arr.length() == 0) return@withContext null
        val r = arr.getJSONObject(0)
        val id = r.optInt("id")
        val detail = runCatching { HugoApi.get("/tmdb/details/tv/$id") }.getOrNull()
        val d = detail?.let { JSONObject(it) }
        val genres = d?.optJSONArray("genres")?.let { g ->
            (0 until g.length()).joinToString(", ") { g.getJSONObject(it).optString("name") }
        }
        Info(
            overview = d?.optString("overview")?.takeIf { it.isNotBlank() } ?: r.optString("overview").takeIf { it.isNotBlank() },
            genres = genres?.takeIf { it.isNotBlank() },
            rating = r.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) },
            year = r.optString("first_air_date").take(4).takeIf { it.length == 4 },
            poster = r.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" },
        )
    }

    suspend fun similarMovies(title: String): List<Similar> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(clean(title), "UTF-8")
        val search = runCatching { HugoApi.get("/tmdb/search?type=movie&q=$q") }.getOrNull() ?: return@withContext emptyList()
        val arr = JSONObject(search).optJSONArray("results") ?: return@withContext emptyList()
        if (arr.length() == 0) return@withContext emptyList()
        val id = arr.getJSONObject(0).optInt("id")
        val sim = runCatching { HugoApi.get("/tmdb/details/movie/$id") }.getOrNull() ?: return@withContext emptyList()
        // O endpoint de details não traz similares; retorna vazio por enquanto
        // (o VPS pode ser estendido depois com /tmdb/similar)
        emptyList()
    }
}
