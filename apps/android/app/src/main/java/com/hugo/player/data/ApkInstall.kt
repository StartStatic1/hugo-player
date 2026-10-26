package com.hugo.player.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Instala o APK baixado SEM depender de "abrir arquivo" (ACTION_VIEW), que em alguns
 * aparelhos cai em outro app (ex.: Termux "Save file in ~/downloads/") em vez do instalador.
 *  1) PackageInstaller (sessão do sistema) → abre a tela "Deseja atualizar este app?" do Android/Fire TV.
 *  2) Se a sessão falhar: intent de instalação apontando só para o instalador do sistema.
 */
object ApkInstall {
    private const val TAG = "HugoInstall"
    const val ACTION_STATUS = "com.hugo.player.INSTALL_STATUS"
    const val MIME_APK = "application/vnd.android.package-archive"

    /** Erro vindo do instalador do sistema (a tela de atualização mostra). */
    val lastError = MutableStateFlow<String?>(null)

    /** null = instalador aberto; senão mensagem de erro. */
    fun install(context: Context, apk: File): String? {
        lastError.value = null
        val viaSession = runCatching { installWithSession(context, apk) }
            .onFailure { Log.w(TAG, "session install failed, fallback", it) }
            .isSuccess
        if (viaSession) return null
        return runCatching { installWithSystemIntent(context, apk); null }
            .getOrElse { Log.e(TAG, "intent install failed", it); "Não foi possível abrir o instalador do Android (${it.message})." }
    }

    private fun installWithSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("hugo-update.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val status = Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(context, sessionId, status, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /** Intent de instalação explícita para o instalador do sistema (nunca para outro app que "abre arquivos"). */
    fun buildSystemInstallIntent(context: Context, apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        }
        systemInstallerPackage(context, intent)?.let { intent.setPackage(it) }
        return intent
    }

    private fun installWithSystemIntent(context: Context, apk: File) {
        context.startActivity(buildSystemInstallIntent(context, apk))
    }

    /** Pacote do instalador do sistema que atende a intent (ignora apps instalados pelo usuário). */
    private fun systemInstallerPackage(context: Context, intent: Intent): String? {
        val pm = context.packageManager
        val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return list.map { it.activityInfo }
            .firstOrNull { (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
            ?.packageName
    }
}

/** Resposta do PackageInstaller: pede a confirmação do usuário (tela do sistema) ou informa erro. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }.onFailure {
                        ApkInstall.lastError.value = "Não foi possível abrir a tela de instalação. Tente de novo."
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstall.lastError.value = null
            PackageInstaller.STATUS_FAILURE_ABORTED -> ApkInstall.lastError.value = "Instalação cancelada. Toque em Baixar e instalar para tentar de novo."
            PackageInstaller.STATUS_FAILURE_STORAGE -> ApkInstall.lastError.value = "Sem espaço no aparelho para instalar a atualização."
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                ApkInstall.lastError.value = "O Android recusou a atualização (versão incompatível). Desinstale e instale de novo pelo site."
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("HugoInstall", "install status=$status msg=$msg")
                ApkInstall.lastError.value = "Falha na instalação (${msg ?: status}). Tente de novo."
            }
        }
    }
}
