package com.hugo.player.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vencimento da conta do provedor (Xtream user_info.exp_date), por playlist.
 * Vem junto com a lista ao carregar/atualizar. M3U sem essa informação: nada guardado, nada mostrado.
 */
object ListExpiry {
    private const val UNLIMITED = -1L
    private const val DAY = 86_400_000L

    enum class Level { OK, SOON, EXPIRED, UNLIMITED }
    data class Info(val expiresAtMs: Long?, val level: Level, val label: String, val shortLabel: String)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("hugo_list_expiry", Context.MODE_PRIVATE)

    /** expiresAtMs null + unlimited = "Ilimitado"; account ausente (M3U) = apaga. */
    fun save(c: Context, playlistId: String, expiresAtMs: Long?, unlimited: Boolean) {
        val v = when { expiresAtMs != null && expiresAtMs > 0 -> expiresAtMs; unlimited -> UNLIMITED; else -> null }
        prefs(c).edit().apply { if (v == null) remove(playlistId) else putLong(playlistId, v) }.apply()
    }

    fun clear(c: Context, playlistId: String) { prefs(c).edit().remove(playlistId).apply() }

    fun get(c: Context, playlistId: String, now: Long = System.currentTimeMillis()): Info? {
        val p = prefs(c)
        if (!p.contains(playlistId)) return null
        return describe(p.getLong(playlistId, 0L), now)
    }

    /** Regra pura (testável): ≤ 7 dias = amarelo, vencida = vermelho. */
    fun describe(stored: Long, now: Long): Info? {
        if (stored == UNLIMITED) return Info(null, Level.UNLIMITED, "Lista sem vencimento", "sem vencimento")
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
}
