package com.hugo.player.data.epg

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import android.util.Xml
import com.hugo.player.data.ApiException
import com.hugo.player.data.AppSettings
import com.hugo.player.data.CatalogRepository
import com.hugo.player.data.DeviceIdentity
import com.hugo.player.data.HugoApi
import com.hugo.player.data.PlaylistRepository
import com.hugo.player.data.db.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

data class EpgProgram(val title: String, val start: Long, val stop: Long, val desc: String? = null) {
    fun isLive(at: Long = System.currentTimeMillis()) = start <= at && stop > at
    fun progress(at: Long = System.currentTimeMillis()): Float =
        if (stop <= start) 0f else ((at - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/** channelId = id do canal no XMLTV (usado para pedir a programação completa). */
data class EpgNowNext(val channelId: String, val now: EpgProgram?, val next: EpgProgram?)

/**
 * EPG da aba TV ao vivo.
 * 1) Pergunta ao backend (/epg/now-next e /epg/channel), que usa o link próprio da playlist
 *    ou o xmltv.php do Xtream automaticamente.
 * 2) Se a VPS for bloqueada pelo provedor (upstream_403 etc.), baixa o XMLTV direto no aparelho
 *    (gzip ou não), só com os canais desta playlist e janela de -2h a +24h.
 * Casamento: tvg-id / epg_channel_id primeiro, depois nome normalizado.
 */
object LiveEpg {
    private const val TAG = "LiveEpg"
    private const val ENTRY_TTL_MS = 15 * 60_000L
    private const val DEVICE_TTL_MS = 6 * 3_600_000L

    private class Entry(val value: EpgNowNext?, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    /** playlists em que o EPG é baixado no aparelho (VPS bloqueada) */
    private val deviceMode = ConcurrentHashMap<String, Boolean>()
    /** playlists sem EPG nenhum (não pergunta de novo por um tempo) */
    private val unavailable = ConcurrentHashMap<String, Long>()
    private val deviceEpg = ConcurrentHashMap<String, DeviceEpg>()
    private val deviceLock = Mutex()

    fun invalidate(playlistId: String) {
        cache.keys.removeAll { it.startsWith("$playlistId|") }
        deviceMode.remove(playlistId)
        unavailable.remove(playlistId)
        deviceEpg.remove(playlistId)
    }

    private fun key(playlistId: String, c: ChannelEntity) = "$playlistId|${c.url}"

    /** O que já está em cache (para pintar a lista sem esperar a rede). */
    fun cached(playlistId: String, c: ChannelEntity): EpgNowNext? {
        val e = cache[key(playlistId, c)] ?: return null
        return e.value?.let { refreshNow(it) }
    }

    /** Se o "agora" acabou, promove o "a seguir". */
    private fun refreshNow(v: EpgNowNext): EpgNowNext {
        val t = System.currentTimeMillis()
        if (v.now != null && v.now.stop <= t && v.next != null && v.next.isLive(t)) return v.copy(now = v.next, next = null)
        return v
    }

    private fun fresh(e: Entry?): Boolean {
        if (e == null) return false
        val t = System.currentTimeMillis()
        if (t - e.at > ENTRY_TTL_MS) return false
        val now = e.value?.now
        return now == null || now.stop > t || e.value?.next?.isLive(t) == true
    }

    /** Agora / a seguir para vários canais (resultado por url do canal). */
    suspend fun nowNext(context: Context, playlistId: String, channels: List<ChannelEntity>): Map<String, EpgNowNext> {
        val out = HashMap<String, EpgNowNext>()
        val missing = ArrayList<ChannelEntity>()
        for (c in channels) {
            val e = cache[key(playlistId, c)]
            if (fresh(e)) e?.value?.let { out[c.url] = refreshNow(it) } else missing.add(c)
        }
        if (missing.isEmpty()) return out
        unavailable[playlistId]?.let { if (System.currentTimeMillis() - it < ENTRY_TTL_MS) return out }
        try {
            val fetched = if (deviceMode[playlistId] == true) deviceNowNext(context, playlistId, missing)
            else try {
                remoteNowNext(context, playlistId, missing)
            } catch (e: ApiException) {
                if (shouldFallBack(e)) {
                    Log.i(TAG, "VPS sem acesso ao EPG (${e.apiError}); baixando no aparelho")
                    deviceMode[playlistId] = true
                    deviceNowNext(context, playlistId, missing)
                } else throw e
            }
            val t = System.currentTimeMillis()
            for (c in missing) {
                val v = fetched[c.url]
                cache[key(playlistId, c)] = Entry(v, t)
                if (v != null) out[c.url] = v
            }
        } catch (e: Exception) {
            Log.w(TAG, "EPG indisponível: ${e.message}")
            unavailable[playlistId] = System.currentTimeMillis()
        }
        return out
    }

    /** Próximos programas de um canal (lista curta no painel de prévia). */
    suspend fun guide(context: Context, playlistId: String, channel: ChannelEntity, limit: Int = 8): List<EpgProgram> {
        val nn = nowNext(context, playlistId, listOf(channel))[channel.url] ?: return emptyList()
        return try {
            if (deviceMode[playlistId] == true) {
                deviceEpg[playlistId]?.programmes?.get(nn.channelId).orEmpty()
                    .filter { it.stop > System.currentTimeMillis() }.take(limit)
            } else {
                val id = DeviceIdentity.get(context)
                val json = JSONObject(
                    HugoApi.get(
                        "/epg/channel/${HugoApi.enc(nn.channelId)}?playlistId=${HugoApi.enc(playlistId)}&limit=$limit",
                        id.deviceId, id.deviceKey, readTimeoutMs = 130_000,
                    )
                )
                parsePrograms(json.optJSONArray("programmes"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "guia: ${e.message}")
            listOfNotNull(nn.now, nn.next)
        }
    }

    private fun shouldFallBack(e: ApiException): Boolean {
        val code = e.apiError ?: return false
        return code.startsWith("upstream_4") || code == "epg_fetch_failed" || code == "timeout_fetching_epg"
    }

    // ---------------- backend ----------------

    private suspend fun remoteNowNext(context: Context, playlistId: String, channels: List<ChannelEntity>): Map<String, EpgNowNext> {
        val id = DeviceIdentity.get(context)
        val out = HashMap<String, EpgNowNext>()
        for (chunk in channels.chunked(400)) {
            val ids = JSONArray()
            val seen = HashSet<String>()
            for (c in chunk) {
                c.epgId?.takeIf { it.isNotBlank() && seen.add(it) }?.let { ids.put(it) }
                if (seen.add(c.name)) ids.put(c.name)
            }
            val res = JSONObject(
                HugoApi.post(
                    "/epg/now-next?playlistId=${HugoApi.enc(playlistId)}",
                    JSONObject().put("ids", ids).toString(), id.deviceId, id.deviceKey,
                    readTimeoutMs = 130_000,
                )
            )
            if (!res.optBoolean("available", true)) {
                unavailable[playlistId] = System.currentTimeMillis()
                return out
            }
            val map = res.optJSONObject("channels") ?: continue
            for (c in chunk) {
                val o = c.epgId?.let { map.optJSONObject(it) } ?: map.optJSONObject(c.name) ?: continue
                out[c.url] = EpgNowNext(
                    channelId = o.optString("channelId"),
                    now = o.optJSONObject("now")?.let(::parseProgram),
                    next = o.optJSONObject("next")?.let(::parseProgram),
                )
            }
        }
        return out
    }

    private fun parsePrograms(arr: JSONArray?): List<EpgProgram> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseProgram) }

    private fun parseProgram(o: JSONObject): EpgProgram? {
        val start = isoMs(o.optString("start")) ?: return null
        val stop = isoMs(o.optString("stop")) ?: return null
        return EpgProgram(o.optString("title", "Sem título"), start, stop, o.optString("desc", "").takeIf { it.isNotBlank() })
    }

    private fun isoMs(s: String): Long? = try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        f.parse(s)?.time
    } catch (_: Exception) { null }

    // ---------------- no aparelho ----------------

    private class DeviceEpg(
        val loadedAt: Long,
        val programmes: Map<String, List<EpgProgram>>,
        val idsLower: Map<String, String>,
        val byName: Map<String, String>,
    )

    private suspend fun deviceNowNext(context: Context, playlistId: String, channels: List<ChannelEntity>): Map<String, EpgNowNext> {
        val epg = loadDevice(context, playlistId) ?: return emptyMap()
        val t = System.currentTimeMillis()
        val out = HashMap<String, EpgNowNext>()
        for (c in channels) {
            val chId = c.epgId?.lowercase()?.let { epg.idsLower[it] } ?: epg.byName[epgNameKey(c.name)] ?: continue
            val list = epg.programmes[chId].orEmpty()
            val i = list.indexOfFirst { it.stop > t }
            if (i < 0) continue
            val now = list[i].takeIf { it.start <= t }
            val next = if (now != null) list.getOrNull(i + 1) else list[i]
            out[c.url] = EpgNowNext(chId, now, next)
        }
        return out
    }

    private suspend fun loadDevice(context: Context, playlistId: String): DeviceEpg? = deviceLock.withLock {
        deviceEpg[playlistId]?.let { if (System.currentTimeMillis() - it.loadedAt < DEVICE_TTL_MS) return@withLock it }
        val src = deviceSource(context, playlistId) ?: return@withLock null
        val keys = CatalogRepository.get(context).liveEpgKeys(playlistId)
        val ids = keys.mapNotNullTo(HashSet()) { it.epgId?.lowercase() }
        val names = keys.mapTo(HashSet()) { epgNameKey(it.name) }
        val lowRam = isLowRam(context)
        val parsed = withContext(Dispatchers.IO) { download(context, src, ids, names, lowRam) }
        deviceEpg.clear() // só uma lista de EPG na memória
        if (parsed != null) deviceEpg[playlistId] = parsed
        parsed
    }

    /** Link próprio da playlist > o que o backend calcula (xmltv.php) > xmltv.php montado aqui. */
    private suspend fun deviceSource(context: Context, playlistId: String): String? {
        PlaylistRepository.epgUrlFor(playlistId)?.let { return it }
        try {
            val id = DeviceIdentity.get(context)
            val o = JSONObject(HugoApi.get("/epg/source?playlistId=${HugoApi.enc(playlistId)}", id.deviceId, id.deviceKey, 20_000))
            o.optString("epgUrl", "").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        } catch (_: Exception) {}
        val url = AppSettings.lastPlaylistUrl.takeIf { AppSettings.lastPlaylistId == playlistId } ?: return null
        return xtreamXmltv(url)
    }

    fun xtreamXmltv(playlistUrl: String): String? = try {
        val u = URL(playlistUrl)
        val q = (u.query ?: "").split("&").mapNotNull {
            val p = it.split("=", limit = 2); if (p.size == 2) p[0].lowercase() to URLDecoder.decode(p[1], "UTF-8") else null
        }.toMap()
        val user = q["username"]; val pass = q["password"]
        if (user.isNullOrBlank() || pass.isNullOrBlank()) null
        else "${u.protocol}://${u.authority}/xmltv.php?username=${HugoApi.enc(user)}&password=${HugoApi.enc(pass)}"
    } catch (_: Exception) { null }

    private fun isLowRam(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isLowRamDevice || am.memoryClass <= 128
    }

    private fun download(context: Context, src: String, ids: Set<String>, names: Set<String>, lowRam: Boolean): DeviceEpg? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(src).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
            conn.setRequestProperty("Accept", "*/*")
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "EPG no aparelho: HTTP ${conn.responseCode}")
                return null
            }
            val raw = BufferedInputStream(conn.inputStream, 64 * 1024)
            raw.mark(2)
            val b0 = raw.read(); val b1 = raw.read()
            raw.reset()
            val input: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
            input.use { parse(it, ids, names, lowRam) }
        } catch (e: Exception) {
            Log.w(TAG, "EPG no aparelho falhou: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(input: InputStream, ids: Set<String>, names: Set<String>, lowRam: Boolean): DeviceEpg {
        val now = System.currentTimeMillis()
        val from = now - 2 * 3_600_000L
        val to = now + (if (lowRam) 12 else 24) * 3_600_000L
        val maxProgrammes = if (lowRam) 40_000 else 150_000
        val filter = ids.isNotEmpty() || names.isNotEmpty()
        val kept = HashSet<String>()
        val idsLower = HashMap<String, String>()
        val byName = HashMap<String, String>()
        val programmes = HashMap<String, ArrayList<EpgProgram>>()
        var count = 0

        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var chId: String? = null
        val chNames = ArrayList<String>()
        var progCh: String? = null
        var progStart = 0L; var progStop = 0L
        var title: String? = null; var desc: String? = null
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> { chId = p.getAttributeValue(null, "id"); chNames.clear() }
                        "display-name" -> if (chId != null) p.nextText()?.trim()?.takeIf { it.isNotEmpty() }?.let { chNames.add(it) }
                        "programme" -> {
                            val ch = p.getAttributeValue(null, "channel")
                            val s = xmltvTime(p.getAttributeValue(null, "start"))
                            val e = xmltvTime(p.getAttributeValue(null, "stop")) ?: s?.plus(3_600_000L)
                            val wanted = ch != null && (!filter || kept.contains(ch) || ids.contains(ch.lowercase()))
                            if (wanted && s != null && e != null && e > from && s < to && count < maxProgrammes) {
                                progCh = ch; progStart = s; progStop = e; title = null; desc = null
                            } else progCh = null
                        }
                        "title" -> if (progCh != null && title == null) title = p.nextText()?.trim()
                        "desc" -> if (progCh != null && desc == null && !lowRam) desc = p.nextText()?.trim()?.let { if (it.length > 200) it.take(199) + "…" else it }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> {
                            val id = chId
                            if (!id.isNullOrEmpty()) {
                                val nameKeys = chNames.map(::epgNameKey)
                                val accept = !filter || ids.contains(id.lowercase()) || nameKeys.any { names.contains(it) }
                                if (accept) {
                                    kept.add(id)
                                    idsLower.putIfAbsent(id.lowercase(), id)
                                    nameKeys.forEach { if (it.isNotEmpty()) byName.putIfAbsent(it, id) }
                                }
                            }
                            chId = null
                        }
                        "programme" -> {
                            val ch = progCh
                            if (ch != null) {
                                programmes.getOrPut(ch) { ArrayList() }
                                    .add(EpgProgram(title?.takeIf { it.isNotEmpty() } ?: "Sem título", progStart, progStop, desc?.takeIf { it.isNotEmpty() }))
                                count++
                                idsLower.putIfAbsent(ch.lowercase(), ch)
                            }
                            progCh = null
                        }
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            // XML de IPTV às vezes vem quebrado no fim: fica com o que já leu
            Log.w(TAG, "XMLTV parcial ($count programas): ${e.message}")
        }
        programmes.values.forEach { it.sortBy { x -> x.start } }
        Log.i(TAG, "EPG no aparelho: ${kept.size} canais, $count programas")
        return DeviceEpg(System.currentTimeMillis(), programmes, idsLower, byName)
    }

    private val timeRe = Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([+-])?(\\d{2})?(\\d{2})?")

    fun xmltvTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val m = timeRe.find(s.trim()) ?: return null
        val g = m.groupValues
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        var t = cal.timeInMillis
        if (g[7].isNotEmpty()) {
            val sign = if (g[7] == "-") -1 else 1
            t -= sign * ((g[8].ifEmpty { "0" }.toInt() * 60 + g[9].ifEmpty { "0" }.toInt()) * 60_000L)
        }
        return t
    }

    private val marks = Regex("[\\u0300-\\u036f\\u064b-\\u065f\\u0670]")
    private val brackets = Regex("\\[.*?]|\\(.*?\\)")
    private val quality = Regex("\\b(fhd|uhd|hd|sd|4k|h265|hevc)\\b")
    private val nonAlnum = Regex("[^a-z0-9\\u0600-\\u06ff]+")

    /** Igual ao epgNameKey do backend: sem acento, minúsculo, sem tags de qualidade. */
    fun epgNameKey(name: String): String {
        val n = marks.replace(Normalizer.normalize(name, Normalizer.Form.NFD), "").lowercase().trim()
        return nonAlnum.replace(quality.replace(brackets.replace(n, " "), " "), "")
    }
}
