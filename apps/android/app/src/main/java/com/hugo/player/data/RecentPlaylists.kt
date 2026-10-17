package com.hugo.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Playlists abertas recentemente (mostradas na Home). */
object RecentPlaylists {
    data class Entry(val id: String, val name: String, val openedAt: Long)

    private const val KEY = "recentPlaylists"
    private const val MAX = 6

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hugo", Context.MODE_PRIVATE)

    fun list(context: Context): List<Entry> = try {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("id"), o.optString("name", "Playlist"), o.optLong("openedAt"))
        }
    } catch (_: Exception) { emptyList() }

    private fun save(context: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.take(MAX).forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("openedAt", it.openedAt)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun touch(context: Context, id: String, name: String) =
        save(context, listOf(Entry(id, name, System.currentTimeMillis())) + list(context).filterNot { it.id == id })

    fun rename(context: Context, id: String, name: String) =
        save(context, list(context).map { if (it.id == id) it.copy(name = name) else it })

    fun remove(context: Context, id: String) = save(context, list(context).filterNot { it.id == id })
}
