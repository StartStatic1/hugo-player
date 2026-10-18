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
)

object AppUpdate {
    private const val TAG = "HugoUpdate"

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

    /** Baixa o APK e abre o instalador do sistema. */
    suspend fun downloadAndInstall(context: Context, apkUrl: String): String? =
        withContext(Dispatchers.IO) {
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

                val dir = context.getExternalFilesDir("updates") ?: context.filesDir
                val file = File(dir, "update.apk")
                if (file.exists()) file.delete()
                file.parentFile?.mkdirs()

                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                try {
                    if (conn.responseCode !in 200..299) {
                        return@withContext "Falha ao baixar APK (HTTP ${conn.responseCode})."
                    }
                    conn.inputStream.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                } finally {
                    conn.disconnect()
                }

                if (!file.exists() || file.length() < 10_000) {
                    return@withContext "APK inválido ou incompleto."
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_NEW_TASK,
                        )
                    }
                    context.startActivity(intent)
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                e.message ?: "Erro ao instalar."
            }
        }
}
