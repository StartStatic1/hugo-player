package com.hugo.player.data

import com.hugo.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** apiError: código bruto do backend (ex.: "upstream_403"), quando houver. */
class ApiException(val status: Int, message: String, val apiError: String? = null) : Exception(message)

/** Cliente HTTP mínimo para o backend do Hugo Player. */
object HugoApi {
    val baseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/')

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    suspend fun get(
        path: String,
        deviceId: String? = null,
        deviceKey: String? = null,
        readTimeoutMs: Int = 90_000,
    ): String = request("GET", path, null, deviceId, deviceKey, readTimeoutMs)

    suspend fun post(
        path: String,
        body: String,
        deviceId: String? = null,
        deviceKey: String? = null,
        readTimeoutMs: Int = 60_000,
    ): String = request("POST", path, body, deviceId, deviceKey, readTimeoutMs)

    /** PATCH via POST + X-HTTP-Method-Override (HttpURLConnection não suporta PATCH). */
    suspend fun patch(
        path: String,
        body: String,
        deviceId: String? = null,
        deviceKey: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = request("POST", path, body, deviceId, deviceKey, 30_000, headers + ("X-HTTP-Method-Override" to "PATCH"))

    /** DELETE via POST + X-HTTP-Method-Override (mais compatível com proxies e servidores). */
    suspend fun delete(
        path: String,
        deviceId: String? = null,
        deviceKey: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = request("POST", path, "{}", deviceId, deviceKey, 30_000, headers + ("X-HTTP-Method-Override" to "DELETE"))

    suspend fun send(
        method: String,
        path: String,
        body: String?,
        deviceId: String?,
        deviceKey: String?,
        headers: Map<String, String> = emptyMap(),
    ): String = request(method, path, body, deviceId, deviceKey, 30_000, headers)

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        deviceId: String?,
        deviceKey: String?,
        readTimeoutMs: Int,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("x-hugo-version-code", BuildConfig.VERSION_CODE.toString())
            // HttpURLConnection descompacta gzip automaticamente
            if (deviceId != null) conn.setRequestProperty("x-device-id", deviceId)
            if (deviceKey != null) conn.setRequestProperty("x-device-key", deviceKey)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code == 426) AppUpdate.requestRecheck()
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val apiErr = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(err)?.groupValues?.get(1)
                throw ApiException(code, friendlyApiError(code, apiErr), apiErr)
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun friendlyApiError(code: Int, apiErr: String?): String = when {
        code == 426 || apiErr == "update_required" -> "Atualize o Hugo Player para continuar."
        code == 401 -> "Dispositivo não autorizado. Ative novamente no site."
        apiErr == "invalid_pin" -> "PIN incorreto."
        apiErr == "pin_required" -> "Esta playlist exige PIN."
        apiErr == "too_many_pin_attempts" || code == 429 -> "Muitas tentativas. Aguarde um minuto."
        apiErr == "playlist_not_found" -> "Playlist não encontrada."
        apiErr == "invalid_url" -> "URL inválida."
        apiErr == "validation_error" -> "Verifique os dados informados."
        code == 404 && apiErr == "not_found" -> "Função indisponível: atualize o servidor do Hugo Player."
        apiErr == "timeout_fetching_playlist" || code == 504 -> "O servidor da lista demorou demais para responder."
        apiErr == "empty_playlist" -> "A lista está vazia ou não é uma M3U válida."
        apiErr == "playlist_too_large" -> "A lista é grande demais."
        apiErr?.startsWith("upstream_4") == true -> "O provedor recusou o acesso à lista (${apiErr.removePrefix("upstream_")}). Verifique se ela está ativa."
        apiErr?.startsWith("upstream_") == true -> "Não foi possível acessar o servidor da lista."
        apiErr == "missing_playlist" -> "Playlist não encontrada."
        else -> "Erro no servidor (HTTP $code${apiErr?.let { ": $it" } ?: ""})."
    }
}
