package com.hugo.player.vpn

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/**
 * VPN no próprio vídeo: quando o stream (filme, série ou canal) responde 403/451 — bloqueio por
 * país/IP — liga a VPN WireGuard (config do servidor ou importada) e o player tenta 1x de novo.
 * Mesma regra do download do catálogo: só com o automático ligado e a VPN ainda desligada.
 */
object VpnPlayback {
    private const val TAG = "HugoVpn"
    /** falhou (sem config, permissão negada, túnel não subiu): não tenta de novo por 2 min (evita pedir permissão a cada canal) */
    private const val FAIL_BACKOFF_MS = 2 * 60_000L
    @Volatile private var lastFailAt = 0L

    /** Código HTTP de bloqueio (403/451) em qualquer nível da cadeia de causas, ou null. */
    @OptIn(UnstableApi::class)
    fun geoBlockStatus(t: Throwable?): Int? =
        geoBlockStatus(t) { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }

    internal fun geoBlockStatus(t: Throwable?, codeOf: (Throwable) -> Int?): Int? {
        var e = t; var depth = 0
        while (e != null && depth < 8) {
            codeOf(e)?.let { return if (VpnManager.isGeoBlock(it)) it else null }
            e = e.cause; depth++
        }
        return null
    }

    /** Vale tentar a VPN agora? (não abre diálogo nem rede) */
    fun canTry(now: Long = System.currentTimeMillis()): Boolean =
        VpnManager.isEnabled && VpnManager.autoEnabled && !VpnManager.isUp && now - lastFailAt !in 0 until FAIL_BACKOFF_MS

    /**
     * Baixa/confere a config, pede a permissão (só na 1ª vez) e liga o túnel.
     * true = ligou: a fonte fica marcada como "precisa do país" e o player deve tentar de novo.
     * Nunca lança exceção.
     */
    suspend fun connectFor(sourceId: String?, code: Int): Boolean {
        if (!canTry()) return false
        Log.d(TAG, "vídeo respondeu $code: tentando com VPN")
        val ok = runCatching { VpnManager.prepareConfig() && VpnManager.ensureConnected() }.getOrDefault(false)
        if (!ok) {
            lastFailAt = System.currentTimeMillis()
            Log.d(TAG, "VPN não ligou para o vídeo: segue o erro normal")
            return false
        }
        // conexões abertas antes do túnel continuam fora dele: descarta para o player abrir novas pela VPN
        runCatching { com.hugo.player.net.SecureNet.client.connectionPool.evictAll() }
        sourceId?.let { VpnManager.rememberRequired(it, VpnManager.activeCountry.value) }
        Log.d(TAG, "VPN ${VpnManager.activeCountry.value} ligada para o vídeo: tentando de novo")
        return true
    }
}
