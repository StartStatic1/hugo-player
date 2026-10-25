package com.hugo.player.data

import android.content.Context
import java.security.MessageDigest

/**
 * Bloqueio por PIN das categorias adultas (ao vivo, filmes e séries).
 * A categoria é detectada pelo nome; o PIN é pedido uma vez por sessão (até fechar o app).
 */
object AdultLock {
    private const val PREFS = "hugo_lock"
    private val adultRegex = Regex("((?<!\\p{L})adult[oa]?s?(?!\\p{L})|xxx|\\+\\s?18(?!\\d)|(?<!\\d)18\\s?\\+|(?<!\\p{L})porn)", RegexOption.IGNORE_CASE)

    @Volatile var unlockedThisSession = false
        private set

    fun isAdultCategory(name: String?): Boolean = name != null && adultRegex.containsMatchIn(name)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false) && hasPin(ctx)
    fun hasPin(ctx: Context): Boolean = !prefs(ctx).getString("pin", null).isNullOrBlank()

    /** Bloqueado agora (ligado e ainda não desbloqueado nesta sessão). */
    fun isLocked(ctx: Context): Boolean = isEnabled(ctx) && !unlockedThisSession

    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("enabled", on).apply(); if (on) unlockedThisSession = false }

    fun setPin(ctx: Context, pin: String) {
        prefs(ctx).edit().putString("pin", hash(ctx, pin)).apply()
        unlockedThisSession = false
    }

    fun check(ctx: Context, pin: String): Boolean = prefs(ctx).getString("pin", null) == hash(ctx, pin)

    /** Confere e, se certo, libera até fechar o app. */
    fun unlock(ctx: Context, pin: String): Boolean = check(ctx, pin).also { if (it) unlockedThisSession = true }

    fun validPinFormat(pin: String): Boolean = pin.length in 4..6 && pin.all { it.isDigit() }

    private fun hash(ctx: Context, pin: String): String {
        val salt = prefs(ctx).getString("salt", null) ?: java.util.UUID.randomUUID().toString().also { prefs(ctx).edit().putString("salt", it).apply() }
        return MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
