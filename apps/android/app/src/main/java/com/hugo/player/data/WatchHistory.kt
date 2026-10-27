package com.hugo.player.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object WatchHistory {
    private const val PREFS = "hugo_watch"
    private const val KEY_FAVS = "favorites"
    private const val KEY_FAV_META = "fav_meta"
    private const val KEY_PROGRESS = "progress"
    private const val MAX_CONTINUE = 30
    private const val KEY_WATCHED = "watched"
    private const val MAX_WATCHED = 3000
    /** A partir daqui conta como "assistido". */
    const val WATCHED_FRACTION = 0.92f

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
        /** Episódio: "T1 · E03 · Título" (cards do Continuar assistindo). */
        val subtitle: String? = null,
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

    fun removeFavorite(ctx: Context, key: String) {
        val p = prefs(ctx)
        val arr = JSONArray(p.getString(KEY_FAVS, "[]"))
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) if (arr.optString(i) != key) list.add(arr.optString(i))
        val root = JSONObject(p.getString(KEY_FAV_META, "{}"))
        root.remove(key)
        p.edit().putString(KEY_FAVS, JSONArray(list).toString()).putString(KEY_FAV_META, root.toString()).apply()
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
            FavoriteItem(k, o.optString("title"), o.optString("url"), o.optString("logo").takeIf { it.isNotBlank() }, o.optString("playlistId"))
        }
    }

    fun saveProgress(ctx: Context, key: String, title: String, url: String, positionMs: Long, durationMs: Long, playlistId: String, isLive: Boolean = false, logo: String? = null, subtitle: String? = null) {
        if (isLive) return
        if (durationMs > 0 && positionMs.toFloat() / durationMs >= WATCHED_FRACTION) { markWatched(ctx, key); removeProgress(ctx, key); return }
        if (positionMs < 15_000) { removeProgress(ctx, key); return }
        val p = prefs(ctx)
        val root = JSONObject(p.getString(KEY_PROGRESS, "{}"))
        root.put(key, JSONObject().put("title", title).put("url", url).put("positionMs", positionMs).put("durationMs", durationMs).put("playlistId", playlistId).put("updatedAt", System.currentTimeMillis()).put("logo", logo ?: "").put("subtitle", subtitle ?: ""))
        if (root.length() > MAX_CONTINUE) {
            val sorted = root.keys().asSequence().map { k -> k to root.getJSONObject(k).optLong("updatedAt") }.sortedBy { it.second }.map { it.first }.toList()
            val drop = sorted.size - MAX_CONTINUE
            for (i in 0 until drop) root.remove(sorted[i])
        }
        p.edit().putString(KEY_PROGRESS, root.toString()).apply()
    }

    /** Marca como assistido (≥ 92% ou terminou). */
    fun markWatched(ctx: Context, key: String) {
        val p = prefs(ctx)
        val arr = JSONArray(p.getString(KEY_WATCHED, "[]"))
        val list = ArrayList<String>(arr.length() + 1)
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it != key }?.let(list::add)
        list.add(key)
        val trimmed = if (list.size > MAX_WATCHED) list.subList(list.size - MAX_WATCHED, list.size) else list
        p.edit().putString(KEY_WATCHED, JSONArray(trimmed).toString()).apply()
    }

    fun watchedKeys(ctx: Context): Set<String> {
        val arr = JSONArray(prefs(ctx).getString(KEY_WATCHED, "[]"))
        val out = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    /** Progresso parcial (0..1) por chave, para a barrinha dos episódios. */
    fun progressFractions(ctx: Context): Map<String, Float> {
        val root = JSONObject(prefs(ctx).getString(KEY_PROGRESS, "{}"))
        val out = HashMap<String, Float>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = root.optJSONObject(k) ?: continue
            val d = o.optLong("durationMs"); val pos = o.optLong("positionMs")
            if (d > 0) out[k] = (pos.toFloat() / d).coerceIn(0f, 1f)
        }
        return out
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

    fun clearContinue(ctx: Context) { prefs(ctx).edit().remove(KEY_PROGRESS).apply() }

    /** Episódio de série? (o subtítulo salvo começa com "T2 · E05" ou "Temporada 2"). */
    fun isEpisode(item: ProgressItem): Boolean =
        item.subtitle?.let { Regex("""^(T\d+ · E\d+|Temporada \d+)""").containsMatchIn(it) } == true

    private fun seriesGroup(item: ProgressItem) = "${item.playlistId}|${item.title}"

    /** Home: uma série = um cartão (o episódio mais recente). Entrada já vem do mais novo para o mais velho. */
    fun groupContinue(items: List<ProgressItem>): List<ProgressItem> {
        val seen = HashSet<String>()
        return items.filter { !isEpisode(it) || seen.add(seriesGroup(it)) }
    }

    /** Tira o item do Continuar assistindo; numa série, tira todos os episódios dela pela metade. */
    fun removeContinue(ctx: Context, item: ProgressItem) {
        val keys = if (isEpisode(item)) listContinue(ctx).filter { isEpisode(it) && seriesGroup(it) == seriesGroup(item) }.map { it.key }
        else listOf(item.key)
        val p = prefs(ctx)
        val root = JSONObject(p.getString(KEY_PROGRESS, "{}"))
        (keys + item.key).forEach { root.remove(it) }
        p.edit().putString(KEY_PROGRESS, root.toString()).apply()
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
            out.add(ProgressItem(k, o.optString("title"), o.optString("url"), o.optLong("positionMs"), o.optLong("durationMs"), pid, o.optLong("updatedAt"), logo = o.optString("logo").takeIf { it.isNotBlank() }, subtitle = o.optString("subtitle").takeIf { it.isNotBlank() }))
        }
        return out.sortedByDescending { it.updatedAt }
    }
}
