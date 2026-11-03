package com.hugo.player.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS seguro: primeiro o DNS do aparelho (rápido, sem mudança para quem não é bloqueado);
 * se a operadora não achar o endereço (bloqueio por DNS) ou devolver um endereço de bloqueio,
 * pergunta ao Cloudflare (DoH) e depois ao Google (DoH). Guarda o resultado por 10 min.
 *
 * Modos: "auto" (padrão: DoH só quando o DNS do aparelho falha), "on" (sempre DoH primeiro), "off".
 */
object SecureDns : Dns {
    @Volatile var mode: String = "auto"

    private const val TTL_MS = 10 * 60_000L
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    /** hosts que o DNS do aparelho não acha: nas próximas vezes vai direto ao DoH (sem esperar a falha de novo) */
    private val systemBlocked = ConcurrentHashMap<String, Long>()

    private val bootstrap by lazy {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
    }
    private val cloudflare by lazy {
        DnsOverHttps.Builder().client(bootstrap).url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .includeIPv6(true).build()
    }
    private val google by lazy {
        DnsOverHttps.Builder().client(bootstrap).url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
            .includeIPv6(true).build()
    }

    /** Endereços que operadoras usam para "bloquear" (0.0.0.0, localhost, rede interna). */
    internal fun looksBlocked(list: List<InetAddress>): Boolean =
        list.isEmpty() || list.all { a ->
            a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
                (a is Inet4Address && a.isSiteLocalAddress)
        }

    private fun isIpLiteral(host: String) = host.all { it.isDigit() || it == '.' } || host.contains(':')

    private fun doh(host: String): List<InetAddress>? {
        for (r in listOf(cloudflare, google)) {
            val got = runCatching { r.lookup(host) }.getOrNull()
            if (!got.isNullOrEmpty() && !looksBlocked(got)) return got
        }
        return null
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase()
        if (mode == "off" || isIpLiteral(host) || host == "localhost") return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[host]?.let { (exp, v) -> if (exp > now) return v }
        val skipSystem = mode == "on" || (systemBlocked[host]?.let { it > now } == true)
        if (!skipSystem) {
            val sys = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
            if (sys != null && !looksBlocked(sys)) return sys // caminho normal: igual a antes, sem atraso
            systemBlocked[host] = now + TTL_MS
        }
        doh(host)?.let { cache[host] = (now + TTL_MS) to it; return it }
        if (skipSystem) runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()?.takeIf { !looksBlocked(it) }?.let { return it }
        throw UnknownHostException("dns_blocked:$hostname")
    }

    fun clearCache() { cache.clear(); systemBlocked.clear() }
}

/** Uma conexão para listas, login Xtream, EPG e vídeo — tudo com o DNS seguro. */
object SecureNet {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(SecureDns)
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Texto claro quando o erro é bloqueio de endereço (DNS) da operadora. */
    fun isDnsBlock(t: Throwable?): Boolean {
        var e = t; var depth = 0
        while (e != null && depth < 6) { if (e is UnknownHostException) return true; e = e.cause; depth++ }
        return false
    }
    const val BLOCKED_MSG = "A sua internet não encontrou o endereço deste servidor (a operadora pode estar bloqueando). Ative Configurações › DNS seguro › Sempre ligado ou use outra rede."
}
