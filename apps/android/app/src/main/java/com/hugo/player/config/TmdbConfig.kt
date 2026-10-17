package com.hugo.player.config

/**
 * TMDB configuration.
 * Add your API key in local.properties or BuildConfig when ready.
 * Docs: https://developer.themoviedb.org/docs
 */
object TmdbConfig {
    const val BASE_URL = "https://api.themoviedb.org/3/"
    const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    // Set via BuildConfig or SharedPreferences later
    var apiKey: String = ""
}