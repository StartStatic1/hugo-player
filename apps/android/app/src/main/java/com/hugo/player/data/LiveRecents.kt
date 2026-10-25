package com.hugo.player.data

import android.content.Context
import org.json.JSONArray

/** Canais ao vivo assistidos por último (por playlist), para a categoria "Recentes". */
object LiveRecents {
    private const val MAX = 40
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("hugo_live_recents", Context.MODE_PRIVATE)

    fun list(ctx: Context, playlistId: String): List<String> {
        val arr = JSONArray(prefs(ctx).getString(playlistId, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    fun add(ctx: Context, playlistId: String, url: String) {
        if (playlistId.isBlank() || url.isBlank()) return
        val list = (listOf(url) + list(ctx, playlistId).filter { it != url }).take(MAX)
        prefs(ctx).edit().putString(playlistId, JSONArray(list).toString()).apply()
    }

    fun clear(ctx: Context, playlistId: String) { prefs(ctx).edit().remove(playlistId).apply() }
}
