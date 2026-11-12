package com.hugo.player.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.util.Log
import com.hugo.player.BuildConfig
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * VPN WireGuard embutida (GoBackend = wireguard-go dentro do app, sem root).
 *
 * Só liga quando precisa:
 *  - o provedor respondeu 403 no aparelho (CatalogRepository tenta de novo uma vez com a VPN), ou
 *  - a fonte está marcada como "precisa de país X" (backend: `vpnCountry`/`requires_vpn`, ou lembrado
 *    depois que um 403 só passou com a VPN).
 * Sem nenhuma config importada nada muda: todas as funções devolvem false e não abrem diálogo.
 *
 * Configs: um arquivo wg-quick (.conf) por país em filesDir/vpn/<CC>.conf (ex.: US.conf).
 * Também lê <externalFilesDir>/vpn/<CC>.conf (Android/data/<pacote>/files/vpn/US.conf), para colocar
 * o arquivo por gerenciador de arquivos/adb em aparelhos de TV sem seletor de arquivos.
 *
 * O túnel cobre só o tráfego do próprio Hugo (IncludedApplications = pacote do app), a não ser que o
 * .conf já defina IncludedApplications/ExcludedApplications.
 */
object VpnManager {
    private const val TAG = "HugoVpn"
    const val DEFAULT_COUNTRY = "US"
    private const val TUNNEL_NAME = "hugo"
    private const val CONNECT_TIMEOUT_MS = 20_000L
    private const val CONSENT_TIMEOUT_MS = 60_000L
    private const val HANDSHAKE_WAIT_MS = 6_000L

    enum class Status { OFF, CONNECTING, ON, NEEDS_CONSENT, ERROR }

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status
    private val _country = MutableStateFlow<String?>(null)
    /** país do túnel ligado agora (null = desligado) */
    val activeCountry: StateFlow<String?> = _country

    /**
     * Pedido de permissão de VPN do Android (diálogo único do sistema). A MainActivity observa,
     * abre o Intent e chama [onConsentResult]. Sem Activity na tela, o pedido expira e a VPN não liga.
     */
    private val _consentRequest = MutableStateFlow<Intent?>(null)
    val consentRequest: StateFlow<Intent?> = _consentRequest
    private var consentResult: CompletableDeferred<Boolean>? = null

    private lateinit var appContext: Context
    private val backend: GoBackend by lazy { GoBackend(appContext) }
    private val mutex = Mutex()
    private val configs = mutableMapOf<String, Config>()

    private val tunnel = object : Tunnel {
        override fun getName() = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "túnel: $newState")
            if (newState == Tunnel.State.DOWN) { _status.value = Status.OFF; _country.value = null }
        }
    }

    val isEnabled: Boolean get() = BuildConfig.VPN_ENABLED
    val isUp: Boolean get() = _status.value == Status.ON

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    private fun ready() = isEnabled && ::appContext.isInitialized
    private fun prefs() = appContext.getSharedPreferences("hugo_vpn", Context.MODE_PRIVATE)

    /** Liga sozinho quando precisa (padrão: sim). */
    var autoEnabled: Boolean
        get() = ready() && prefs().getBoolean("auto", true)
        set(v) { if (ready()) prefs().edit().putBoolean("auto", v).apply() }

    // ---------- configs por país ----------

    private fun cc(country: String?) = (country ?: DEFAULT_COUNTRY).trim().uppercase().ifEmpty { DEFAULT_COUNTRY }
    private fun internalDir() = File(appContext.filesDir, "vpn")
    private fun configFiles(country: String): List<File> = listOfNotNull(
        File(internalDir(), "$country.conf"),
        appContext.getExternalFilesDir(null)?.let { File(it, "vpn/$country.conf") },
    )

    /** Lê e valida uma config wg-quick. Lança BadConfigException/IOException se for inválida. */
    fun parseConfig(text: String): Config = Config.parse(text.byteInputStream())

    /** Países com config disponível (arquivo interno ou externo). */
    fun countries(): List<String> {
        if (!ready()) return emptyList()
        val dirs = listOfNotNull(internalDir(), appContext.getExternalFilesDir(null)?.let { File(it, "vpn") })
        return dirs.flatMap { d -> d.listFiles { f -> f.name.endsWith(".conf", true) }?.toList().orEmpty() }
            .map { it.name.substringBeforeLast('.').uppercase() }.distinct().sorted()
    }

    fun hasConfig(country: String? = null): Boolean = ready() && loadConfig(cc(country)) != null

    private fun loadConfig(country: String): Config? {
        configs[country]?.let { return it }
        for (f in configFiles(country)) {
            if (!f.isFile) continue
            try {
                val c = f.inputStream().use { Config.parse(it) }
                configs[country] = c
                Log.d(TAG, "config $country carregada de ${f.parentFile?.name}/${f.name} (${c.peers.size} peer)")
                return c
            } catch (e: Exception) {
                Log.w(TAG, "config $country inválida em ${f.path}: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        return null
    }

    /** Salva (valida antes) uma config wg-quick em texto para o país. */
    fun importConfig(country: String?, text: String): Result<String> = runCatching {
        check(ready()) { "VPN indisponível neste build" }
        val c = cc(country)
        parseConfig(text) // valida; nunca registra a chave no log
        internalDir().mkdirs()
        File(internalDir(), "$c.conf").writeText(text)
        configs.remove(c)
        Log.d(TAG, "config $c importada")
        c
    }

    /** Importa de um arquivo escolhido pelo usuário (SAF). */
    fun importConfig(country: String?, uri: Uri): Result<String> = runCatching {
        val text = appContext.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("não foi possível ler o arquivo")
        importConfig(country, text).getOrThrow()
    }

    fun removeConfig(country: String?) {
        if (!ready()) return
        val c = cc(country)
        configFiles(c).forEach { runCatching { it.delete() } }
        configs.remove(c)
    }

    // ---------- fontes que precisam de país ----------

    /** Marca que esta fonte (id da conexão) só funciona com IP do país. null = desmarca. */
    fun rememberRequired(sourceId: String, country: String?) {
        if (!ready()) return
        if (country == null) prefs().edit().remove("req:$sourceId").apply()
        else prefs().edit().putString("req:$sourceId", cc(country)).apply()
    }

    fun requiredCountry(sourceId: String?): String? =
        if (sourceId == null || !ready()) null else prefs().getString("req:$sourceId", null)

    /** Antes de tocar/baixar: liga a VPN se a fonte foi marcada como precisando de país. Rápido quando não precisa. */
    suspend fun ensureForSource(sourceId: String?): Boolean {
        val c = requiredCountry(sourceId) ?: return false
        if (!autoEnabled) return false
        return ensureConnected(c)
    }

    // ---------- ligar / desligar ----------

    /** Precisa do diálogo de permissão de VPN do Android? */
    fun needsConsent(): Boolean = ready() && runCatching { VpnService.prepare(appContext) != null }.getOrDefault(false)

    fun onConsentResult(granted: Boolean) {
        Log.d(TAG, "permissão de VPN: ${if (granted) "concedida" else "negada"}")
        _consentRequest.value = null
        consentResult?.complete(granted)
    }

    /**
     * Garante o túnel do [country] ligado. Devolve true se ficou ligado.
     * Nunca lança exceção: sem config, sem permissão ou erro no túnel = false (e o fluxo normal segue).
     */
    suspend fun ensureConnected(country: String? = null, timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean {
        if (!ready()) return false
        val c = cc(country)
        return try {
            mutex.withLock {
                if (isUp && _country.value == c) return@withLock true
                val base = withContext(Dispatchers.IO) { loadConfig(c) }
                if (base == null) { Log.d(TAG, "sem config para $c, VPN não liga"); return@withLock false }
                if (!obtainConsent()) { _status.value = Status.NEEDS_CONSENT; return@withLock false }
                _status.value = Status.CONNECTING
                Log.d(TAG, "ligando VPN $c...")
                val cfg = scopedToApp(base)
                val ok = withTimeoutOrNull(timeoutMs) {
                    withContext(Dispatchers.IO) {
                        if (_country.value != null && _country.value != c) backend.setState(tunnel, Tunnel.State.DOWN, null)
                        backend.setState(tunnel, Tunnel.State.UP, cfg) == Tunnel.State.UP
                    }
                } ?: false
                if (!ok) {
                    Log.w(TAG, "VPN $c não ligou (tempo esgotado ou recusada)")
                    runCatching { withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) } }
                    _status.value = Status.ERROR; _country.value = null
                    return@withLock false
                }
                _status.value = Status.ON; _country.value = c
                val hs = waitHandshake()
                Log.d(TAG, "VPN $c ligada" + if (hs) " (handshake ok)" else " (sem handshake ainda)")
                true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "falha ao ligar VPN $c: ${e.javaClass.simpleName} ${e.message}")
            _status.value = Status.ERROR; _country.value = null
            false
        }
    }

    suspend fun disconnect() {
        if (!ready()) return
        try {
            mutex.withLock {
                if (_status.value == Status.OFF && _country.value == null) return@withLock
                withContext(Dispatchers.IO) { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                Log.d(TAG, "VPN desligada")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "falha ao desligar VPN: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            _status.value = Status.OFF; _country.value = null
        }
    }

    /** 403/451 do provedor = provável bloqueio por país/IP. */
    fun isGeoBlock(code: Int) = code == 403 || code == 451

    private suspend fun obtainConsent(): Boolean {
        val intent = runCatching { VpnService.prepare(appContext) }.getOrElse {
            Log.w(TAG, "VpnService.prepare falhou: ${it.javaClass.simpleName}"); return false
        } ?: return true
        Log.d(TAG, "pedindo permissão de VPN ao usuário")
        val d = CompletableDeferred<Boolean>()
        consentResult = d
        _consentRequest.value = intent
        val granted = withTimeoutOrNull(CONSENT_TIMEOUT_MS) { d.await() } ?: false
        _consentRequest.value = null
        consentResult = null
        return granted && runCatching { VpnService.prepare(appContext) == null }.getOrDefault(false)
    }

    /** Só o app passa pelo túnel (vídeo de outros apps e o resto do aparelho não são afetados). */
    private fun scopedToApp(c: Config): Config {
        val i = c.`interface`
        if (i.includedApplications.isNotEmpty() || i.excludedApplications.isNotEmpty()) return c
        val b = Interface.Builder()
            .addAddresses(i.addresses)
            .addDnsServers(i.dnsServers)
            .addDnsSearchDomains(i.dnsSearchDomains)
            .setKeyPair(i.keyPair)
            .includeApplication(appContext.packageName)
        i.listenPort.ifPresent { b.setListenPort(it) }
        i.mtu.ifPresent { b.setMtu(it) }
        return Config.Builder().setInterface(b.build()).addPeers(c.peers).build()
    }

    private suspend fun waitHandshake(): Boolean {
        val end = System.currentTimeMillis() + HANDSHAKE_WAIT_MS
        while (System.currentTimeMillis() < end) {
            val ok = runCatching {
                val st = backend.getStatistics(tunnel)
                st.peers().any { (st.peer(it)?.latestHandshakeEpochMillis() ?: 0L) > 0L }
            }.getOrDefault(false)
            if (ok) return true
            delay(300)
        }
        return false
    }
}
