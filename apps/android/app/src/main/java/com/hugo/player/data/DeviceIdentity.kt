package com.hugo.player.data

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.hugo.player.BuildConfig
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * Identidade do dispositivo: Device ID no formato MAC (gerado a partir de um UUID,
 * não é o MAC real do aparelho) + Device Key curta. Gerada uma vez e salva em
 * SharedPreferences "hugo" (deviceId / deviceKey).
 */
object DeviceIdentity {
    data class Identity(val deviceId: String, val deviceKey: String)

    /** Par fixo usado nas versões antigas; migrado para um par próprio. */
    const val LEGACY_ID = "AA:BB:CC:DD:EE:FF"
    const val LEGACY_KEY = "ABC12345"

    private const val PREFS = "hugo"
    private const val KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // sem 0/O/1/I
    private val random = SecureRandom()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Retorna a identidade salva, gerando (ou migrando a antiga fixa) se preciso. */
    @Synchronized
    fun get(context: Context): Identity {
        val p = prefs(context)
        val id = p.getString("deviceId", null)
        val key = p.getString("deviceKey", null)
        if (!id.isNullOrBlank() && !key.isNullOrBlank() && id != LEGACY_ID) return Identity(id, key)

        val fresh = Identity(generateId(), generateKey())
        p.edit().apply {
            putString("deviceId", fresh.deviceId)
            putString("deviceKey", fresh.deviceKey)
            putBoolean("deviceRegistered", false)
            if (id == LEGACY_ID) {
                // Instalação antiga já "ativada" com o par fixo: copia as playlists depois
                putBoolean("legacyMigrationPending", true)
                putBoolean("activationDone", true)
            }
        }.commit()
        return fresh
    }

    fun isActivationDone(context: Context): Boolean = prefs(context).getBoolean("activationDone", false)

    fun setActivationDone(context: Context) {
        prefs(context).edit().putBoolean("activationDone", true).apply()
    }

    /** 6 bytes de um UUID v4, com o bit "localmente administrado" ligado e multicast desligado. */
    fun generateId(): String {
        val u = UUID.randomUUID()
        val buf = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        val bytes = buf.copyOfRange(10, 16) // parte aleatória do UUID
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun generateKey(length: Int = 8): String =
        (1..length).map { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }.joinToString("")

    fun isTv(context: Context): Boolean {
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** Registra o dispositivo no backend (idempotente). Retorna true se ok. */
    suspend fun ensureRegistered(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean("deviceRegistered", false)) return true
        val idt = get(context)
        return try {
            val body = JSONObject()
                .put("deviceId", idt.deviceId)
                .put("deviceKey", idt.deviceKey)
                .put("platform", if (isTv(context)) "android-tv" else "android")
                .put("appVersion", BuildConfig.VERSION_NAME)
            HugoApi.post("/devices/register", body.toString(), readTimeoutMs = 15_000)
            p.edit().putBoolean("deviceRegistered", true).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Copia (uma vez) as playlists do par fixo antigo para a identidade nova,
     * para quem atualizou o app não perder as listas. Não apaga nada do par antigo.
     */
    suspend fun migrateLegacyIfNeeded(context: Context): Int {
        val p = prefs(context)
        if (!p.getBoolean("legacyMigrationPending", false)) return 0
        if (!ensureRegistered(context)) return 0
        val idt = get(context)
        return try {
            val legacy = PlaylistRepository.list(LEGACY_ID, LEGACY_KEY)
            val mine = PlaylistRepository.list(idt.deviceId, idt.deviceKey).map { it.url }.toSet()
            var copied = 0
            legacy.filter { !it.url.isNullOrBlank() && it.url !in mine }.forEach {
                PlaylistRepository.add(idt.deviceId, idt.deviceKey, it.name, it.url!!)
                copied++
            }
            p.edit().putBoolean("legacyMigrationPending", false).apply()
            copied
        } catch (e: ApiException) {
            // 401 = par antigo não existe no servidor: nada a migrar
            if (e.status == 401) p.edit().putBoolean("legacyMigrationPending", false).apply()
            0
        } catch (e: Exception) {
            0
        }
    }
}
