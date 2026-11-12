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
    /** Link próprio do EPG (XMLTV); null = automático (xmltv.php do Xtream / url-tvg). */
    val epgUrl: String? = null,
    /** País exigido pelo provedor (ex.: "US"): a VPN WireGuard liga sozinha para esta fonte. Opcional no JSON. */
    val vpnCountry: String? = null,
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
        epgUrl = o.optString("epgUrl", "").takeIf { it.isNotBlank() && it != "null" },
        vpnCountry = vpnCountryOf(o),
    ).also { p ->
        if (!p.isProtected || p.url != null) rememberEpgLink(p.id, p.epgUrl)
        // só marca (nunca desmarca): o app também aprende sozinho quando um 403 passa com a VPN
        p.vpnCountry?.let { com.hugo.player.vpn.VpnManager.rememberRequired(p.id, it) }
    }

    /** `vpnCountry: "US"` ou `requires_vpn`/`requiresVpn`: true (= US) ou "US". Ausente/false = null. */
    internal fun vpnCountryOf(o: JSONObject): String? {
        o.optString("vpnCountry", "").trim().takeIf { it.length == 2 }?.let { return it.uppercase() }
        for (k in listOf("requires_vpn", "requiresVpn")) {
            val v = o.opt(k)?.toString()?.trim().orEmpty()
            if (v.equals("true", true)) return com.hugo.player.vpn.VpnManager.DEFAULT_COUNTRY
            if (v.length == 2 && v.all { it.isLetter() }) return v.uppercase()
        }
        return null
    }

    /**
     * Último link de EPG conhecido por playlist (para o EPG baixado no aparelho).
     * ConcurrentHashMap não aceita valor null: sem link = remove a chave
     * (guardar null derrubava a lista de playlists com NullPointerException).
     */
    private val epgLinks = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun epgUrlFor(playlistId: String): String? = epgLinks[playlistId]
    internal fun rememberEpgLink(playlistId: String, epgUrl: String?) {
        val v = epgUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (v == null) epgLinks.remove(playlistId) else epgLinks[playlistId] = v
    }

    /** Aceita vazio (= automático) ou http(s)://host... */
    fun isValidEpgUrl(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        return Regex("^https?://[^\\s/?#]+[^\\s]*$", RegexOption.IGNORE_CASE).matches(t)
    }

    suspend fun list(deviceId: String, deviceKey: String): List<RemotePlaylist> {
        val json = JSONObject(HugoApi.get("/playlists", deviceId, deviceKey, readTimeoutMs = 20_000))
        val arr = json.optJSONArray("playlists") ?: return emptyList()
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    suspend fun add(deviceId: String, deviceKey: String, name: String, url: String, epgUrl: String? = null): RemotePlaylist {
        val body = JSONObject().put("name", name).put("type", "m3u").put("url", url)
        epgUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("epgUrl", it) }
        val json = JSONObject(HugoApi.post("/playlists", body.toString(), deviceId, deviceKey, readTimeoutMs = 20_000))
        return parse(json.getJSONObject("playlist").apply { if (!has("name")) put("name", name) })
    }

    suspend fun rename(deviceId: String, deviceKey: String, playlistId: String, name: String, pin: String? = null) {
        HugoApi.patch("/playlists/${HugoApi.enc(playlistId)}", JSONObject().put("name", name).toString(), deviceId, deviceKey, pinHeader(playlistId, pin))
        pin?.let { rememberPin(playlistId, it) }
    }

    /** Nome e link do EPG (vazio = volta ao automático). */
    suspend fun update(deviceId: String, deviceKey: String, playlistId: String, name: String, epgUrl: String, pin: String? = null) {
        val body = JSONObject().put("name", name).put("epgUrl", epgUrl.trim())
        HugoApi.patch("/playlists/${HugoApi.enc(playlistId)}", body.toString(), deviceId, deviceKey, pinHeader(playlistId, pin))
        pin?.let { rememberPin(playlistId, it) }
        rememberEpgLink(playlistId, epgUrl)
        com.hugo.player.data.epg.LiveEpg.invalidate(playlistId)
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
            series = repo.seriesCount(playlistId, null, null),
            syncedAt = syncedAt(context, playlistId),
        ).also { s -> prefs(context).edit().putString("sum_$playlistId", "${s.live},${s.movies},${s.series}").apply() }
    }

    /** Último resumo lido do banco: o card mostra na hora, sem esperar contar ~80 mil itens de novo. */
    fun cachedSummary(context: Context, playlistId: String): LocalSummary? {
        val p = prefs(context).getString("sum_$playlistId", null)?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return null
        if (p.size != 3) return null
        return LocalSummary(p[0], p[1], p[2], syncedAt(context, playlistId))
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hugo_sync", Context.MODE_PRIVATE)

    fun syncedAt(context: Context, playlistId: String): Long? =
        prefs(context).getLong("sync_$playlistId", 0L).takeIf { it > 0 }

    fun markSynced(context: Context, playlistId: String) {
        prefs(context).edit().putLong("sync_$playlistId", System.currentTimeMillis()).apply()
    }

    /** Esquece quando cada playlist foi baixada (usado pelo "Limpar" das Configurações). */
    fun clearAllSync(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun clearSync(context: Context, playlistId: String) {
        prefs(context).edit().remove("sync_$playlistId").apply()
    }
}
