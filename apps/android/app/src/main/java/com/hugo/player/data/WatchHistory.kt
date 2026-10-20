package com.hugo.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favoritos e progresso de reprodução (continuar assistindo).
 * Persistido em SharedPreferences "hugo_watch".
 */
object WatchHistory {
    private const val PREFS = "hugo_watch"
    private const val KEY_FAVS = "favorites"
    private const val KEY_FAV_META = "fav_meta"
    private const val KEY_PROGRESS = "progress"
    private const val MAX_CONTINUE = 30

    data class ProgressItem(
        val key: String,
        val title: String,
        val url: String,
        val positionMs: Long,
        val durationMs: Long,
        val playlistId: String,
        val updatedAt: Long,
        val isLive: Boolean = false,
        val logo: String? = null,
    ) {
        val fraction: Float
            get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    data class FavoriteItem(
        val key: String,
        val title: String,
        val url: String,
        val logo: String?,
        val playlistId: String,
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun contentKey(playlistId: String, url: String): String =
        "$playlistId|${url.hashCode()}"

    // ── Favoritos ───────────────────────────────────────────────

    fun isFavorite(ctx: Context, key: String): Boolean {
        val arr = JSONArray(prefs(ctx).getString(KEY_FAVS, "[]"))
        for (i in 0 until arr.length()) if (arr.optString(i) == key) return true
        return false
    }

    fun toggleFavorite(ctx: Context, key: String): Boolean {
        val p = prefs(ctx)
        val arr = JSONArray(p.getString(KEY_FAVS, "[]"))
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.optString(i))
        val nowFav = if (list.contains(key)) {
            list.remove(key)
            false
        } else {
            list.add(0, key)
            true
        }
        p.edit().putString(KEY_FAVS, JSONArray(list).toString()).apply()
        return nowFav
    }

    fun saveFavoriteMeta(ctx: Context, key: String, title: String, url: String, logo: String?, playlistId: String) {
        val p = prefs(ctx)
        val root = JSONObject(p.getString(KEY_FAV_META, "{}"))
        root.put(key, JSONObject().put("title", title).put("url", url).put("logo", logo ?: "").put("playlistId", playlistId))
        p.edit().putString(KEY_FAV_META, root.toString()).apply()
    }

    fun favoriteKeys(ctx: Context): Set<String> {
        val arr = JSONArray(prefs(ctx).getString(KEY_FAVS, "[]"))
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    fun listFavorites(ctx: Context): List<FavoriteItem> {
        val keys = favoriteKeys(ctx)
        val root = JSONObject(prefs(ctx).getString(KEY_FAV_META, "{}"))
        return keys.mapNotNull { k ->
            val o = root.optJSONObject(k) ?: return@mapNotNull null
            FavoriteItem(
                key = k,
                title = o.optString("title"),
                url = o.optString("url"),
                logo = o.optString("logo").takeIf { it.isNotBlank() },
                playlistId = o.optString("playlistId"),
            )
        }
    }

    // ── Continuar assistindo ──────────────────────────────────────

    fun saveProgress(
        ctx: Context,
        key: String,
        title: String,
        url: String,
        positionMs: Long,
        durationMs: Long,
        playlistId: String,
        isLive: Boolean = false,
        logo: String? = null,
    ) {
        if (isLive) return
        if (positionMs < 15_000) {
            removeProgress(ctx, key)
            return
        }
        if (durationMs > 0 && positionMs.toFloat() / durationMs > 0.92f) {
            removeProgress(ctx, key)
            return
        }
        val p = prefs(ctx)
        val root = JSONObject(p.getString(KEY_PROGRESS, "{}"))
        root.put(
            key,
            JSONObject()
                .put("title", title)
                .put("url", url)
                .put("positionMs", positionMs)
                .put("durationMs", durationMs)
                .put("playlistId", playlistId)
                .put("updatedAt", System.currentTimeMillis())
                .put("logo", logo ?: ""),
        )
        if (root.length() > MAX_CONTINUE) {
            val sorted = root.keys().asSequence().map { k ->
                k to root.getJSONObject(k).optLong("updatedAt")
            }.sortedBy { it.second }.map { it.first }.toList()
            val drop = sorted.size - MAX_CONTINUE
            for (i in 0 until drop) root.remove(sorted[i])
        }
        p.edit().putString(KEY_PROGRESS, root.toString()).apply()
    }

    fun getProgress(ctx: Context, key: String): Long {
        val root = JSONObject(prefs(ctx).getString(KEY_PROGRESS, "{}"))
        if (!root.has(key)) return 0L
        return root.getJSONObject(key).optLong("positionMs", 0L)
    }

    fun removeProgress(ctx: Context, key: String) {
        val p = prefs(ctx)
        val root = JSONObject(p.getString(KEY_PROGRESS, "{}"))
        root.remove(key)
        p.edit().putString(KEY_PROGRESS, root.toString()).apply()
    }

    fun clearContinue(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PROGRESS).apply()
    }

    fun listContinue(ctx: Context, playlistId: String? = null): List<ProgressItem> {
        val root = JSONObject(prefs(ctx).getString(KEY_PROGRESS, "{}"))
        val out = mutableListOf<ProgressItem>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = root.getJSONObject(k)
            val pid = o.optString("playlistId")
            if (playlistId != null && pid != playlistId) continue
            out.add(
                ProgressItem(
                    key = k,
                    title = o.optString("title"),
                    url = o.optString("url"),
                    positionMs = o.optLong("positionMs"),
                    durationMs = o.optLong("durationMs"),
                    playlistId = pid,
                    updatedAt = o.optLong("updatedAt"),
                    logo = o.optString("logo").takeIf { it.isNotBlank() },
                ),
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }
}
