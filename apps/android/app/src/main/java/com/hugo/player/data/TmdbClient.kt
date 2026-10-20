package com.hugo.player.data

import com.hugo.player.config.ApiKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

object TmdbClient {
    private val KEY get() = ApiKeys.TMDB_API_KEY
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/w500"

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

    private fun yearOf(title: String): String? {
        val m = Regex("\\((\\d{4})\\)|\\b((19|20)\\d{2})\\b").find(title) ?: return null
        return m.groupValues.firstOrNull { it.length == 4 }
    }

    suspend fun searchMovie(title: String): Info? = withContext(Dispatchers.IO) {
        if (KEY.isBlank()) return@withContext null
        val q = URLEncoder.encode(clean(title), "UTF-8")
        val year = yearOf(title)
        val url = "$BASE/search/movie?api_key=$KEY&language=pt-BR&query=$q" + (year?.let { "&year=$it" } ?: "")
        val body = get(url) ?: return@withContext null
        val arr = JSONObject(body).optJSONArray("results") ?: return@withContext null
        if (arr.length() == 0) return@withContext null
        val r = arr.getJSONObject(0)
        val id = r.optInt("id")
        val detail = get("$BASE/movie/$id?api_key=$KEY&language=pt-BR")
        val d = detail?.let { JSONObject(it) }
        val genres = d?.optJSONArray("genres")?.let { g ->
            (0 until g.length()).joinToString(", ") { g.getJSONObject(it).optString("name") }
        }
        Info(
            overview = d?.optString("overview")?.takeIf { it.isNotBlank() } ?: r.optString("overview").takeIf { it.isNotBlank() },
            genres = genres?.takeIf { it.isNotBlank() },
            rating = r.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) },
            year = r.optString("release_date").take(4).takeIf { it.length == 4 },
            poster = r.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMG + it },
        )
    }

    suspend fun searchTv(title: String): Info? = withContext(Dispatchers.IO) {
        if (KEY.isBlank()) return@withContext null
        val q = URLEncoder.encode(clean(title), "UTF-8")
        val body = get("$BASE/search/tv?api_key=$KEY&language=pt-BR&query=$q") ?: return@withContext null
        val arr = JSONObject(body).optJSONArray("results") ?: return@withContext null
        if (arr.length() == 0) return@withContext null
        val r = arr.getJSONObject(0)
        val id = r.optInt("id")
        val detail = get("$BASE/tv/$id?api_key=$KEY&language=pt-BR")
        val d = detail?.let { JSONObject(it) }
        val genres = d?.optJSONArray("genres")?.let { g ->
            (0 until g.length()).joinToString(", ") { g.getJSONObject(it).optString("name") }
        }
        Info(
            overview = d?.optString("overview")?.takeIf { it.isNotBlank() } ?: r.optString("overview").takeIf { it.isNotBlank() },
            genres = genres?.takeIf { it.isNotBlank() },
            rating = r.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) },
            year = r.optString("first_air_date").take(4).takeIf { it.length == 4 },
            poster = r.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMG + it },
        )
    }

    suspend fun similarMovies(title: String): List<Similar> = withContext(Dispatchers.IO) {
        if (KEY.isBlank()) return@withContext emptyList()
        val q = URLEncoder.encode(clean(title), "UTF-8")
        val year = yearOf(title)
        val searchUrl = "$BASE/search/movie?api_key=$KEY&language=pt-BR&query=$q" + (year?.let { "&year=$it" } ?: "")
        val search = get(searchUrl) ?: return@withContext emptyList()
        val arr = JSONObject(search).optJSONArray("results") ?: return@withContext emptyList()
        if (arr.length() == 0) return@withContext emptyList()
        val id = arr.getJSONObject(0).optInt("id")
        val sim = get("$BASE/movie/$id/similar?api_key=$KEY&language=pt-BR") ?: return@withContext emptyList()
        val sArr = JSONObject(sim).optJSONArray("results") ?: return@withContext emptyList()
        (0 until minOf(sArr.length(), 12)).map { i ->
            val o = sArr.getJSONObject(i)
            Similar(
                title = o.optString("title"),
                poster = o.optString("poster_path").takeIf { it.isNotBlank() }?.let { IMG + it },
                year = o.optString("release_date").take(4).takeIf { it.length == 4 },
                id = o.optInt("id"),
            )
        }
    }

    private fun get(url: String): String? = try {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        if (conn.responseCode !in 200..299) null
        else conn.inputStream.bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }
}
