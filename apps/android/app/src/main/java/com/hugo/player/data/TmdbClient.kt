package com.hugo.player.data

import com.hugo.player.config.ApiKeys
import com.hugo.player.config.TmdbConfig
import org.json.JSONObject
import java.net.URL

object TmdbClient {
    suspend fun searchMovie(query: String): String? {
        if (ApiKeys.TMDB_API_KEY.isBlank()) return null
        val url = "${TmdbConfig.BASE_URL}search/movie?api_key=${ApiKeys.TMDB_API_KEY}&query=${query.encode()}"
        return fetch(url)
    }

    suspend fun searchTv(query: String): String? {
        if (ApiKeys.TMDB_API_KEY.isBlank()) return null
        val url = "${TmdbConfig.BASE_URL}search/tv?api_key=${ApiKeys.TMDB_API_KEY}&query=${query.encode()}"
        return fetch(url)
    }

    private fun fetch(url: String): String? {
        return try {
            URL(url).readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun String.encode() = java.net.URLEncoder.encode(this, "UTF-8")
}