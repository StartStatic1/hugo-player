package com.hugo.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.hugo.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    /** true = versão instalada abaixo da mínima do servidor: bloqueia o app até atualizar */
    val mandatory: Boolean = false,
    /** bloqueio veio do último aviso salvo (sem internet agora) */
    val offline: Boolean = false,
)

object AppUpdate {
    private const val TAG = "HugoUpdate"
    private const val PREFS = "hugo_update"

    /** Sobe quando o servidor responde 426 (app velho demais): a tela de atualização confere de novo. */
    val recheck = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun requestRecheck() { recheck.value = recheck.value + 1 }

    /**
     * Decide se o app deve ser bloqueado. Só bloqueia quando o servidor diz claramente que
     * a versão é menor que a mínima. Sem internet: usa o último aviso salvo (se já estava
     * bloqueado, continua; se não, deixa usar).
     */
    internal fun decide(local: Int, remote: Int?, min: Int?, cachedMin: Int): Pair<Boolean, Boolean> {
        // retorna (bloquear, veioDoCache)
        if (remote == null || min == null) return (local < cachedMin) to true
        return (min > 0 && local < min) to false
    }

    /** Checa a versão e já trata o bloqueio (salva a mínima para o caso de ficar sem internet). */
    suspend fun checkWithGate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val local = BuildConfig.VERSION_CODE
        val json = fetchVersionJson()
        if (json == null) {
            val cachedMin = p.getInt("minVersionCode", 0)
            val (block, _) = decide(local, null, null, cachedMin)
            return@withContext if (block) UpdateInfo(
                versionCode = p.getInt("versionCode", cachedMin),
                versionName = p.getString("versionName", "").orEmpty(),
                notes = "",
                apkUrl = p.getString("apkUrl", null) ?: "${HugoApi.baseUrl}/channels/latest.apk",
                mandatory = true,
                offline = true,
            ) else null
        }
        SupportLink.save(context, json.optString("supportWhatsappUrl", ""))
        val remote = json.optInt("versionCode", 0)
        val min = json.optInt("minVersionCode", 0)
        val apkUrl = json.optString("apkUrl", "${HugoApi.baseUrl}/channels/latest.apk")
        p.edit().putInt("minVersionCode", min).putInt("versionCode", remote)
            .putString("versionName", json.optString("versionName", "")).putString("apkUrl", apkUrl).apply()
        val (block, _) = decide(local, remote, min, 0)
        Log.i(TAG, "remote=$remote min=$min local=$local block=$block")
        if (!block && remote <= local) return@withContext null
        UpdateInfo(
            versionCode = remote,
            versionName = json.optString("versionName", ""),
            notes = json.optString("notes", "Nova versão disponível."),
            apkUrl = apkUrl,
            mandatory = block,
        )
    }

    /** Link do APK mais novo: pergunta ao servidor; sem resposta usa o último salvo (ou o latest.apk do servidor). */
    suspend fun latestApkUrl(context: Context): String = withContext(Dispatchers.IO) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fallback = "${HugoApi.baseUrl}/channels/latest.apk"
        val json = fetchVersionJson()
        if (json != null) {
            SupportLink.save(context, json.optString("supportWhatsappUrl", ""))
            val url = json.optString("apkUrl", "").ifBlank { fallback }
            p.edit().putString("apkUrl", url).apply()
            url
        } else p.getString("apkUrl", null)?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun fetchVersionJson(): JSONObject? = try {
        val conn = (URL("${HugoApi.baseUrl}/channels/version").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-hugo-version-code", BuildConfig.VERSION_CODE.toString())
        }
        try {
            if (conn.responseCode !in 200..299) null
            else JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).takeIf { !it.has("error") && it.has("versionCode") }
        } finally { conn.disconnect() }
    } catch (e: Exception) {
        Log.w(TAG, "version check failed: ${e.message}")
        null
    }

    /** Busca /channels/version no servidor (sempre em IO). */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "${HugoApi.baseUrl}/channels/version"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    Log.w(TAG, "version HTTP ${conn.responseCode}")
                    return@withContext null
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                if (json.has("error")) {
                    Log.w(TAG, "version error: ${json.optString("error")}")
                    return@withContext null
                }
                val remote = json.optInt("versionCode", 0)
                val local = BuildConfig.VERSION_CODE
                Log.i(TAG, "remote=$remote local=$local")
                if (remote <= local) return@withContext null
                UpdateInfo(
                    versionCode = remote,
                    versionName = json.optString("versionName", ""),
                    notes = json.optString("notes", "Nova versão disponível."),
                    apkUrl = json.optString(
                        "apkUrl",
                        "${HugoApi.baseUrl}/channels/latest.apk",
                    ),
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "check failed", e)
            null
        }
    }

    /** Baixa o APK e abre o instalador do sistema. Etapas (verificando/baixando/abrindo) vão para [onStep]. */
    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onStep: ((UpdateStep) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        suspend fun step(s: UpdateStep) { if (onStep != null) withContext(Dispatchers.Main) { onStep(s) } }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                withContext(Dispatchers.Main) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                return@withContext "Permita instalar apps deste app e tente de novo."
            }

            // armazenamento interno do app (sempre existe; o FileProvider tem files-path/updates)
            val dir = File(context.filesDir, "updates").apply { mkdirs() }
            val file = File(dir, "hugo-update.apk")
            if (file.exists()) file.delete()
            file.parentFile?.mkdirs()

            step(UpdateStep.Downloading(0, -1))
            val t0 = System.currentTimeMillis()
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) {
                    return@withContext "Falha ao baixar a atualização (HTTP ${conn.responseCode}). Tente de novo."
                }
                val total = conn.contentLengthLong
                step(UpdateStep.Downloading(0, total))
                conn.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastAt = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastAt >= PROGRESS_EVERY_MS) { lastAt = now; step(UpdateStep.Downloading(done, total)) }
                        }
                        step(UpdateStep.Downloading(done, total))
                        if (total > 0 && done < total) return@withContext "O download foi interrompido. Verifique a internet e tente de novo."
                    }
                }
            } finally {
                conn.disconnect()
            }
            Log.i(TAG, "apk ${file.length()} bytes em ${System.currentTimeMillis() - t0} ms")

            if (!file.exists() || file.length() < 10_000) {
                return@withContext "A atualização baixada veio incompleta. Tente de novo."
            }

            step(UpdateStep.Opening)
            // a cópia para a sessão do PackageInstaller (~15 MB) roda aqui em IO, sem congelar a tela
            val err = ApkInstall.install(context, file)
            if (err == null) step(UpdateStep.Opened)
            err
        } catch (e: java.net.UnknownHostException) {
            "Sem conexão com a internet. Conecte o aparelho e tente de novo."
        } catch (e: java.net.SocketTimeoutException) {
            "A internet está lenta ou caiu. Tente de novo."
        } catch (e: java.io.IOException) {
            "Falha ao baixar (${e.message ?: "rede"}). Verifique a internet e tente de novo."
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            e.message ?: "Erro ao instalar."
        }
    }

    private const val PROGRESS_EVERY_MS = 250L
}

/** Etapa do "Baixar e instalar" (para a barra de progresso). */
sealed interface UpdateStep {
    data object Idle : UpdateStep
    data object Checking : UpdateStep
    /** total < 0 = servidor não informou o tamanho → barra sem % */
    data class Downloading(val done: Long, val total: Long) : UpdateStep {
        val percent: Int? get() = updatePercent(done, total)
    }
    data object Opening : UpdateStep
    data object Opened : UpdateStep
    data class Failed(val message: String) : UpdateStep
}

internal fun updatePercent(done: Long, total: Long): Int? =
    if (total <= 0) null else (done * 100 / total).toInt().coerceIn(0, 100)

internal fun formatMb(bytes: Long): String =
    String.format(java.util.Locale("pt", "BR"), "%.1f MB", bytes / 1_048_576.0)

/** Texto da etapa (o mesmo no popup e nas telas de atualização). */
internal fun updateStepText(step: UpdateStep): String = when (step) {
    UpdateStep.Idle -> ""
    UpdateStep.Checking -> "Verificando a versão mais nova…"
    is UpdateStep.Downloading -> {
        val pct = step.percent
        when {
            pct != null -> "Baixando atualização… $pct% (${formatMb(step.done)} de ${formatMb(step.total)})"
            step.done > 0 -> "Baixando atualização… ${formatMb(step.done)}"
            else -> "Baixando atualização…"
        }
    }
    UpdateStep.Opening -> "Abrindo instalador…"
    UpdateStep.Opened -> "Instalador aberto. Confirme \"Atualizar\" na tela do Android."
    is UpdateStep.Failed -> step.message
}
