package com.hugo.player.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status devolvido pelo backend para exibição no app. */
data class LicenseStatus(
    val type: String,
    val plan: String,
    val active: Boolean,
    val expiresAt: String?,
) {
    fun label(): String = when {
        type == "premium" && plan == "lifetime" -> "Premium Vitalício"
        type == "premium" && plan == "semiannual" -> "Premium 6 meses"
        type == "premium" -> "Premium Anual"
        type == "expired" -> "Licença expirada"
        else -> "Teste"
    }

    fun validityLabel(): String = when {
        plan == "lifetime" && type == "premium" -> "Sem vencimento"
        !expiresAt.isNullOrBlank() -> "Válido até ${formatDate(expiresAt)}"
        else -> "Status ativo"
    }

    private fun formatDate(value: String): String = runCatching {
        val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
        val output = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
        output.format(input.parse(value) ?: Date())
    }.getOrElse { value.take(10).split('-').reversed().joinToString("/") }

    companion object {
        fun fromResponse(json: String): LicenseStatus {
            val license = JSONObject(json).getJSONObject("license")
            return LicenseStatus(
                type = license.optString("type", "trial"),
                plan = license.optString("plan", "trial"),
                active = license.optBoolean("active", false),
                expiresAt = license.optString("expiresAt", "").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}

object LicenseRepository {
    suspend fun status(deviceId: String, deviceKey: String): LicenseStatus =
        LicenseStatus.fromResponse(HugoApi.get("/devices/status", deviceId, deviceKey, readTimeoutMs = 20_000))
}
