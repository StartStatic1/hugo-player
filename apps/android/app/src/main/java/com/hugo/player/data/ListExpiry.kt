package com.hugo.player.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vencimento da conta do provedor (Xtream user_info.exp_date), por playlist.
 *
 * Vem junto com a lista ao atualizar e, além disso, é conferido sozinho (consulta leve, só o
 * login da player_api — não baixa a lista nem mexe no cache) quando nunca foi visto ou tem
 * mais de 12 h. Estados: não verificado (nada/“Verificando…”), sem data do provedor, com data, M3U.
 */
object ListExpiry {
    private const val UNLIMITED = -1L
    private const val NOT_XTREAM = -2L
    private const val DAY = 86_400_000L
    const val STALE_MS = 12 * 3_600_000L

    enum class Level { OK, SOON, EXPIRED, UNLIMITED }
    data class Info(val expiresAtMs: Long?, val level: Level, val label: String, val shortLabel: String)

    enum class State { UNCHECKED, NOT_XTREAM, NO_DATE, HAS_DATE }

    /** Muda quando algum vencimento chega: telas observam para reler. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("hugo_list_expiry", Context.MODE_PRIVATE)

    /** expiresAtMs > 0 = data; senão unlimited = sem data do provedor. Marca a hora da conferência. */
    fun save(c: Context, playlistId: String, expiresAtMs: Long?, unlimited: Boolean) {
        val v = when { expiresAtMs != null && expiresAtMs > 0 -> expiresAtMs; unlimited -> UNLIMITED; else -> NOT_XTREAM }
        store(c, playlistId, v)
    }

    /** Lista sem essa informação (M3U): conferido, nada a mostrar. */
    fun markNotXtream(c: Context, playlistId: String) = store(c, playlistId, NOT_XTREAM)

    /** Mantido para o sync de M3U: igual a markNotXtream. */
    fun clear(c: Context, playlistId: String) = markNotXtream(c, playlistId)

    private fun store(c: Context, playlistId: String, v: Long) {
        prefs(c).edit().putLong(playlistId, v).putLong("checked_$playlistId", System.currentTimeMillis()).apply()
        _version.value = _version.value + 1
    }

    fun state(c: Context, playlistId: String): State {
        val p = prefs(c)
        if (!p.contains("checked_$playlistId") || !p.contains(playlistId)) return State.UNCHECKED
        return when (val v = p.getLong(playlistId, 0L)) {
            NOT_XTREAM -> State.NOT_XTREAM
            UNLIMITED -> State.NO_DATE
            else -> if (v > 0) State.HAS_DATE else State.UNCHECKED
        }
    }

    fun isStale(c: Context, playlistId: String, now: Long = System.currentTimeMillis()): Boolean {
        val checked = prefs(c).getLong("checked_$playlistId", 0L)
        return checked <= 0 || now - checked > STALE_MS || state(c, playlistId) == State.UNCHECKED
    }

    fun isChecking(playlistId: String) = playlistId in inFlight

    /** Info para mostrar; null quando não verificado ou M3U. */
    fun get(c: Context, playlistId: String, now: Long = System.currentTimeMillis()): Info? = when (state(c, playlistId)) {
        State.HAS_DATE, State.NO_DATE -> describe(prefs(c).getLong(playlistId, 0L), now)
        else -> null
    }

    /** Regra pura (testável): ≤ 7 dias = amarelo, vencida = vermelho. */
    fun describe(stored: Long, now: Long): Info? {
        if (stored == UNLIMITED) return Info(null, Level.UNLIMITED, "Provedor não informa vencimento", "sem data")
        if (stored <= 0) return null
        val date = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(stored))
        val short = SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(Date(stored))
        return when {
            stored <= now -> Info(stored, Level.EXPIRED, "Lista vencida em $date", "vencida $short")
            stored - now <= 7 * DAY -> {
                val days = ((stored - now + DAY - 1) / DAY).toInt()
                Info(stored, Level.SOON, "Vence em $date (${if (days <= 1) "falta 1 dia" else "faltam $days dias"})", "vence $short")
            }
            else -> Info(stored, Level.OK, "Vence em $date", "vence $short")
        }
    }

    /** "2026-11-23T00:00:00.000Z" → epoch ms. */
    fun parseIso(s: String?): Long? {
        if (s.isNullOrBlank() || s == "null") return null
        for (f in listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            val fmt = SimpleDateFormat(f, Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            runCatching { fmt.parse(s)?.time }.getOrNull()?.let { return it }
        }
        return null
    }

    /** exp_date do Xtream: segundos unix em texto ou número; null/""/"0"/"null"/"Ilimitado" = sem data. */
    fun parseExpDate(raw: Any?): Long? {
        if (raw is Number) return raw.toLong().takeIf { it > 0 }?.times(1000)
        val s = raw?.toString()?.trim().orEmpty()
        val secs = if (Regex("""^\d+(\.0+)?$""").matches(s)) s.substringBefore('.').toLongOrNull() else null
        return secs?.takeIf { it > 0 }?.times(1000)
    }

    /** Confere em segundo plano se nunca viu ou tem mais de 12 h. Nunca lança. */
    suspend fun refreshIfStale(c: Context, playlistId: String, playlistUrl: String?) {
        if (playlistId.isBlank() || !isStale(c, playlistId) || !inFlight.add(playlistId)) return
        _version.value = _version.value + 1 // “Verificando…”
        try {
            if (!fromServer(c, playlistId)) fromProvider(c, playlistId, playlistUrl)
        } finally {
            inFlight.remove(playlistId)
            _version.value = _version.value + 1
        }
    }

    /** true = resolvido (data, sem data ou M3U). */
    private suspend fun fromServer(c: Context, playlistId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val identity = DeviceIdentity.get(c)
            val conn = URL("${HugoApi.baseUrl}/channels/account?playlistId=${HugoApi.enc(playlistId)}").openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("x-device-id", identity.deviceId)
            conn.setRequestProperty("x-device-key", identity.deviceKey)
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            try {
                if (conn.responseCode !in 200..299) return@runCatching false
                val j = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                if (!j.optBoolean("xtream", false)) { markNotXtream(c, playlistId); return@runCatching true }
                val a = j.optJSONObject("account") ?: return@runCatching false
                val exp = parseIso(if (a.isNull("expiresAt")) null else a.optString("expiresAt"))
                save(c, playlistId, exp, unlimited = exp == null)
                true
            } finally { conn.disconnect() }
        }.getOrDefault(false)
    }

    /** Provedor bloqueou o servidor (ou servidor fora): pergunta direto do aparelho. */
    suspend fun fromProvider(c: Context, playlistId: String, playlistUrl: String?) = withContext(Dispatchers.IO) {
        runCatching {
            val u = android.net.Uri.parse(playlistUrl ?: return@runCatching)
            val user = u.getQueryParameter("username") ?: return@runCatching
            val pass = u.getQueryParameter("password") ?: return@runCatching
            val api = android.net.Uri.Builder().scheme(u.scheme).encodedAuthority(u.encodedAuthority).path("/player_api.php")
                .appendQueryParameter("username", user).appendQueryParameter("password", pass).build().toString()
            val conn = URL(api).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", AppSettings.userAgent)
            try {
                if (conn.responseCode !in 200..299) return@runCatching
                val ui = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONObject("user_info") ?: return@runCatching
                val exp = parseExpDate(if (ui.isNull("exp_date")) null else ui.opt("exp_date"))
                save(c, playlistId, exp, unlimited = exp == null)
            } finally { conn.disconnect() }
        }
    }
}
