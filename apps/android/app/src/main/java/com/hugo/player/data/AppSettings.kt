package com.hugo.player.data

import android.content.Context
import android.content.SharedPreferences
import com.hugo.player.player.StreamTypes

/** Configurações do app (SharedPreferences "hugo", o mesmo arquivo da ativação). */
object AppSettings {
    private const val PREFS = "hugo"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    val isReady get() = ::prefs.isInitialized

    /** Padrão da validade da lista no aparelho (quem já escolheu outra em Configurações continua com a sua). */
    const val DEFAULT_CATALOG_TTL_HOURS = 48
    val CATALOG_TTL_OPTIONS = listOf(6, 12, 24, 48)

    /** A lista guardada ainda vale? (tem itens, já foi baixada e está dentro da validade escolhida) */
    fun isCatalogFresh(total: Int, syncedAt: Long?, now: Long, ttlHours: Int): Boolean =
        total > 0 && syncedAt != null && syncedAt <= now && now - syncedAt < ttlHours * 3_600_000L

    /** Fundo da legenda: "none" (só contorno, padrão), "translucent" ou "black". */
    var subtitleBackground: String
        get() = (if (isReady) prefs.getString("subtitleBackground", null) else null)?.takeIf { it in SUBTITLE_BACKGROUNDS } ?: "none"
        set(v) { prefs.edit().putString("subtitleBackground", v).apply() }
    val SUBTITLE_BACKGROUNDS = listOf("none", "translucent", "black")

    /** Validade do cache do catálogo em disco, em horas. */
    var catalogTtlHours: Int
        get() = if (isReady) prefs.getInt("catalogTtlHours", DEFAULT_CATALOG_TTL_HOURS) else DEFAULT_CATALOG_TTL_HOURS
        set(v) { prefs.edit().putInt("catalogTtlHours", v).apply() }

    /** User-Agent usado pelo player (lido também por playerUserAgent()). */
    var userAgent: String
        get() = (if (isReady) prefs.getString("userAgent", null) else null)?.takeIf { it.isNotBlank() } ?: StreamTypes.DEFAULT_USER_AGENT
        set(v) { prefs.edit().putString("userAgent", v.trim()).apply() }

    /** Formato dos canais ao vivo em listas Xtream: "m3u8" (HLS) ou "ts". */
    var liveFormat: String
        get() = if (isReady) prefs.getString("liveFormat", "m3u8") ?: "m3u8" else "m3u8"
        set(v) { prefs.edit().putString("liveFormat", if (v == "ts") "ts" else "m3u8").apply() }

    /** Modo de tela do player ao vivo ("fit", "fill", "zoom", "16:9", "4:3"); padrão Ajustar. */
    var liveScreenMode: String
        get() = if (isReady) prefs.getString("liveScreenMode", "fit") ?: "fit" else "fit"
        set(v) { prefs.edit().putString("liveScreenMode", v).apply() }

    /** Abrir automaticamente a última playlist ao iniciar. */
    var autoOpenLastPlaylist: Boolean
        get() = if (isReady) prefs.getBoolean("autoOpenLast", true) else true
        set(v) { prefs.edit().putBoolean("autoOpenLast", v).apply() }

    var lastPlaylistId: String?
        get() = if (isReady) prefs.getString("lastPlaylistId", null) else null
        set(v) { prefs.edit().putString("lastPlaylistId", v).apply() }

    var lastPlaylistName: String?
        get() = if (isReady) prefs.getString("lastPlaylistName", null) else null
        set(v) { prefs.edit().putString("lastPlaylistName", v).apply() }

    var lastPlaylistUrl: String?
        get() = if (isReady) prefs.getString("lastPlaylistUrl", null) else null
        set(v) { prefs.edit().putString("lastPlaylistUrl", v).apply() }
}
