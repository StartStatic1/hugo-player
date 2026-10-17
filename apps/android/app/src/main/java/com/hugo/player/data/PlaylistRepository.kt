package com.hugo.player.data

import android.content.Context
import org.json.JSONObject

/** Playlist salva no backend para este dispositivo. */
data class RemotePlaylist(
    val id: String,
    val name: String,
    val type: String,
    val isProtected: Boolean,
    /** null quando a lista é protegida por PIN e ainda não foi desbloqueada. */
    val url: String?,
    val updatedAt: String?,
)

/** CRUD de playlists no backend + PINs desbloqueados nesta sessão (só em memória). */
object PlaylistRepository {
    private val unlockedPins = mutableMapOf<String, String>()

    fun pinFor(playlistId: String): String? = unlockedPins[playlistId]
    fun rememberPin(playlistId: String, pin: String) { unlockedPins[playlistId] = pin }
    fun forgetPin(playlistId: String) { unlockedPins.remove(playlistId) }

    private fun pinHeader(playlistId: String, pin: String?): Map<String, String> =
        (pin ?: unlockedPins[playlistId])?.let { mapOf("x-playlist-pin" to it) } ?: emptyMap()

    private fun parse(o: JSONObject) = RemotePlaylist(
        id = o.getString("id"),
        name = o.optString("name", "Playlist"),
        type = o.optString("type", "m3u"),
        isProtected = o.optBoolean("protected", false),
        url = o.optString("url", "").takeIf { it.isNotBlank() && it != "null" },
        updatedAt = o.optString("updatedAt", "").takeIf { it.isNotBlank() },
    )

    suspend fun list(deviceId: String, deviceKey: String): List<RemotePlaylist> {
        val json = JSONObject(HugoApi.get("/playlists", deviceId, deviceKey, readTimeoutMs = 20_000))
        val arr = json.optJSONArray("playlists") ?: return emptyList()
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    suspend fun add(deviceId: String, deviceKey: String, name: String, url: String): RemotePlaylist {
        val body = JSONObject().put("name", name).put("type", "m3u").put("url", url)
        val json = JSONObject(HugoApi.post("/playlists", body.toString(), deviceId, deviceKey, readTimeoutMs = 20_000))
        return parse(json.getJSONObject("playlist").apply { if (!has("name")) put("name", name) })
    }

    suspend fun rename(deviceId: String, deviceKey: String, playlistId: String, name: String, pin: String? = null) {
        HugoApi.patch("/playlists/${HugoApi.enc(playlistId)}", JSONObject().put("name", name).toString(), deviceId, deviceKey, pinHeader(playlistId, pin))
        pin?.let { rememberPin(playlistId, it) }
    }

    suspend fun delete(deviceId: String, deviceKey: String, playlistId: String, pin: String? = null) {
        HugoApi.delete("/playlists/${HugoApi.enc(playlistId)}", deviceId, deviceKey, pinHeader(playlistId, pin))
        forgetPin(playlistId)
    }

    /** Confere o PIN e devolve a playlist com a URL. */
    suspend fun unlock(deviceId: String, deviceKey: String, playlistId: String, pin: String): RemotePlaylist {
        val json = JSONObject(
            HugoApi.post("/playlists/${HugoApi.enc(playlistId)}/verify-pin", JSONObject().put("pin", pin).toString(), deviceId, deviceKey, 20_000)
        )
        rememberPin(playlistId, pin)
        return parse(json.getJSONObject("playlist"))
    }

    /** Resumo local (já sincronizado) de uma playlist. */
    data class LocalSummary(val live: Int, val movies: Int, val series: Int, val syncedAt: Long?) {
        val total get() = live + movies + series
    }

    suspend fun localSummary(context: Context, playlistId: String): LocalSummary {
        val repo = CatalogRepository.get(context)
        return LocalSummary(
            live = repo.count(playlistId, "live"),
            movies = repo.count(playlistId, "movie"),
            series = repo.count(playlistId, "series"),
            syncedAt = syncedAt(context, playlistId),
        )
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hugo_sync", Context.MODE_PRIVATE)

    fun syncedAt(context: Context, playlistId: String): Long? =
        prefs(context).getLong("sync_$playlistId", 0L).takeIf { it > 0 }

    fun markSynced(context: Context, playlistId: String) {
        prefs(context).edit().putLong("sync_$playlistId", System.currentTimeMillis()).apply()
    }

    fun clearSync(context: Context, playlistId: String) {
        prefs(context).edit().remove("sync_$playlistId").apply()
    }
}
