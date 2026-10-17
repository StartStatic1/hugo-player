package com.hugo.player.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class EpgProgramme(val title: String, val startMs: Long, val stopMs: Long, val desc: String?) {
    fun progress(nowMs: Long = System.currentTimeMillis()): Float =
        if (stopMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (stopMs - startMs)).coerceIn(0f, 1f)
}

data class NowNext(val now: EpgProgramme?, val next: EpgProgramme?, val fetchedAt: Long)

/**
 * EPG "agora / a seguir" via POST /epg/now-next. O backend lê o XMLTV
 * (url-tvg do M3U ou xmltv.php do Xtream) e devolve só o necessário.
 */
object EpgRepository {
    private const val TTL_MS = 10 * 60_000L
    private val cache = HashMap<String, NowNext>()
    private val missing = HashMap<String, Long>()
    @Volatile private var playlistId: String? = null
    @Volatile var unavailable = false
        private set

    fun key(channel: LiveChannel): String = channel.epgId ?: channel.name

    fun get(key: String): NowNext? = synchronized(cache) { cache[key] }

    /** Busca agora/a seguir para as chaves que ainda não estão em cache (ou expiraram). */
    suspend fun fetch(keys: List<String>): Map<String, NowNext> {
        val k = CatalogRepository.key ?: return emptyMap()
        if (playlistId != k.playlistId) {
            synchronized(cache) { cache.clear(); missing.clear() }
            unavailable = false
            playlistId = k.playlistId
        }
        if (unavailable) return emptyMap()
        val now = System.currentTimeMillis()
        val todo = synchronized(cache) {
            keys.distinct().filter { key ->
                val c = cache[key]
                val stale = c == null || now - c.fetchedAt > TTL_MS || (c.now != null && c.now.stopMs < now)
                val recentlyMissing = (missing[key] ?: 0L) > now - TTL_MS
                stale && !recentlyMissing
            }
        }
        for (chunk in todo.chunked(400)) {
            val body = JSONObject().put("playlistId", k.playlistId).put("ids", JSONArray(chunk)).toString()
            val text = HugoApi.post("/epg/now-next", body, k.deviceId, k.deviceKey, readTimeoutMs = 150_000)
            val json = JSONObject(text)
            if (!json.optBoolean("available", true)) {
                unavailable = true
                break
            }
            val channels = json.optJSONObject("channels") ?: JSONObject()
            synchronized(cache) {
                for (key in chunk) {
                    val o = channels.optJSONObject(key)
                    if (o == null) {
                        missing[key] = now
                        continue
                    }
                    cache[key] = NowNext(parseProgramme(o.optJSONObject("now")), parseProgramme(o.optJSONObject("next")), now)
                }
            }
        }
        return synchronized(cache) { keys.mapNotNull { key -> cache[key]?.let { key to it } }.toMap() }
    }

    // java.time exige API 26; minSdk é 24, então usa SimpleDateFormat (ISO do backend, sempre UTC)
    private fun parseIso(s: String): Long {
        val f = SimpleDateFormat(if (s.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.parse(s)?.time ?: throw IllegalArgumentException(s)
    }

    private fun parseProgramme(o: JSONObject?): EpgProgramme? {
        if (o == null) return null
        return try {
            EpgProgramme(
                title = o.optString("title", ""),
                startMs = parseIso(o.getString("start")),
                stopMs = parseIso(o.getString("stop")),
                desc = o.optString("desc", "").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        }
    }
}
