package com.hugo.player.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Channel(val name: String, val url: String, val logo: String?, val group: String, val type: String = "other")
data class Category(val name: String, val count: Int, val type: String = "other")

object LiveTvCache {
    var channels: List<Channel> = emptyList()
    var categories: List<Category> = emptyList()
    var selectedCategory: String? = null
    var playlistId: String? = null
    var playlistName: String? = null

    fun save(context: Context) {
        try {
            val prefs = context.getSharedPreferences("hugo_cache", Context.MODE_PRIVATE)
            val chArr = JSONArray()
            channels.take(500).forEach { c ->
                chArr.put(JSONObject().apply {
                    put("name", c.name)
                    put("url", c.url)
                    put("logo", c.logo ?: "")
                    put("group", c.group)
                    put("type", c.type)
                })
            }
            val catArr = JSONArray()
            categories.forEach { c ->
                catArr.put(JSONObject().apply {
                    put("name", c.name)
                    put("count", c.count)
                    put("type", c.type)
                })
            }
            prefs.edit()
                .putString("channels", chArr.toString())
                .putString("categories", catArr.toString())
                .putString("playlistId", playlistId)
                .putString("playlistName", playlistName)
                .putString("selectedCategory", selectedCategory)
                .apply()
        } catch (_: Exception) {}
    }

    fun load(context: Context) {
        try {
            val prefs = context.getSharedPreferences("hugo_cache", Context.MODE_PRIVATE)
            val chJson = prefs.getString("channels", null) ?: return
            val catJson = prefs.getString("categories", null) ?: return
            val chArr = JSONArray(chJson)
            val catArr = JSONArray(catJson)
            channels = buildList {
                for (i in 0 until chArr.length()) {
                    val o = chArr.getJSONObject(i)
                    add(Channel(
                        o.getString("name"),
                        o.getString("url"),
                        o.optString("logo").takeIf { it.isNotEmpty() },
                        o.optString("group", "Outros"),
                        o.optString("type", "other")
                    ))
                }
            }
            categories = buildList {
                for (i in 0 until catArr.length()) {
                    val o = catArr.getJSONObject(i)
                    add(Category(o.getString("name"), o.optInt("count", 0), o.optString("type", "other")))
                }
            }
            playlistId = prefs.getString("playlistId", null)
            playlistName = prefs.getString("playlistName", null)
            selectedCategory = prefs.getString("selectedCategory", null)
        } catch (_: Exception) {}
    }

    fun clear() {
        channels = emptyList()
        categories = emptyList()
        selectedCategory = null
        playlistId = null
        playlistName = null
    }
}