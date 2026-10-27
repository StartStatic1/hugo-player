package com.hugo.player.data

import android.content.Context
import java.net.URI

/**
 * Link do suporte (grupo da comunidade no WhatsApp). O servidor manda `supportWhatsappUrl`
 * em /channels/version (muda sem nova versão do app); sem resposta usa o último salvo ou o fixo.
 */
object SupportLink {
    const val DEFAULT_WHATSAPP = "https://chat.whatsapp.com/BRjgrHqSFD008NdzZSMB8P"
    private const val PREFS = "hugo_support"
    private const val KEY = "whatsappUrl"
    private val HOSTS = setOf("chat.whatsapp.com", "wa.me", "api.whatsapp.com")

    /** Só https do WhatsApp, sem parâmetros de rastreio. */
    fun clean(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            val u = URI(s)
            val host = u.host?.lowercase() ?: return null
            if (u.scheme?.lowercase() != "https" || host !in HOSTS) null
            else "https://$host${u.rawPath.orEmpty()}"
        } catch (_: Exception) { null }
    }

    fun whatsapp(c: Context): String =
        clean(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)) ?: DEFAULT_WHATSAPP

    fun save(c: Context, raw: String?) {
        val url = clean(raw) ?: return
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, url).apply()
    }

    /** Texto curto para mostrar na tela (sem "https://"). */
    fun display(url: String): String = url.removePrefix("https://")
}
