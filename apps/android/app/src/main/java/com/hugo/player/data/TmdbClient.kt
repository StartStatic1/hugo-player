package com.hugo.player.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Chama o proxy TMDB do VPS. A chave fica no servidor. */
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

    private fun clean(title: String): String = TmdbMatch.cleanTitle(title)

    /** Melhor resultado que bate nome + ano; null = incerto (a tela mostra os dados do provedor). */
    private fun pick(title: String, arr: org.json.JSONArray?, tv: Boolean): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        val cands = (0 until minOf(arr.length(), 10)).map { i ->
            val o = arr.getJSONObject(i)
            val names = if (tv) listOf(o.optString("name"), o.optString("original_name")) else listOf(o.optString("title"), o.optString("original_title"))
            val y = (if (tv) o.optString("first_air_date") else o.optString("release_date")).take(4).toIntOrNull()
            TmdbMatch.Candidate(i, names.filter { it.isNotBlank() }, y)
        }
        val best = TmdbMatch.pick(title, cands) ?: return null
        return arr.getJSONObject(best.index)
    }

    suspend fun searchMovie(title: String): Info? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(clean(title), "UTF-8")
        val body = runCatching { HugoApi.get("/tmdb/search?type=movie&q=$q") }.getOrNull() ?: return@withContext null
        val r = pick(title, JSONObject(body).optJSONArray("results"), tv = false) ?: return@withContext null
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
        val r = pick(title, JSONObject(body).optJSONArray("results"), tv = true) ?: return@withContext null
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
        val id = pick(title, JSONObject(search).optJSONArray("results"), tv = false)?.optInt("id") ?: return@withContext emptyList()
        val sim = runCatching { HugoApi.get("/tmdb/similar/movie/$id") }.getOrNull() ?: return@withContext emptyList()
        val sArr = JSONObject(sim).optJSONArray("results") ?: return@withContext emptyList()
        (0 until minOf(sArr.length(), 12)).map { i ->
            val o = sArr.getJSONObject(i)
            Similar(
                title = o.optString("title"),
                poster = o.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" },
                year = o.optString("release_date").take(4).takeIf { it.length == 4 },
                id = o.optInt("id"),
            )
        }
    }
}
