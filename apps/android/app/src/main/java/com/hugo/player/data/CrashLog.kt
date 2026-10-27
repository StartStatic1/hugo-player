package com.hugo.player.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda o último fechamento inesperado num arquivo local (Configurações > Diagnóstico e aviso na
 * próxima abertura). Cobre:
 *  - exceção Java/Kotlin em qualquer thread (inclusive OutOfMemoryError, gravado sem alocar muito);
 *  - fechamento "sem erro Java" com o app na tela (falha nativa, decodificador, sistema matando por memória):
 *    detectado na abertura seguinte por uma marca que só é limpa ao sair da tela normalmente.
 * O "último passo" (breadcrumb) ajuda a saber onde estava (ex.: TV ao vivo, prévia).
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val PREFS = "hugo_crash"
    private const val MAX_CHARS = 12_000
    @Volatile private var installed = false
    @Volatile private var lastStep: String = "-"
    @Volatile private var prefs: SharedPreferences? = null
    // reservado para conseguir gravar mesmo em falta de memória
    private var reserve: ByteArray? = ByteArray(256 * 1024)

    /**
     * O que fazer com uma sessão anterior que terminou com a tela aberta (sem exceção Java):
     *  - null = não é falha (o app foi ATUALIZADO, ou o usuário fechou/forçou parar) → não avisa;
     *  - texto = motivo para mostrar no aviso.
     * [prevVersionCode] = versão que estava aberta; [exitReason] = ApplicationExitInfo.reason (Android 11+), ou null.
     */
    internal fun unexpectedExitReason(prevVersionCode: Int, currentVersionCode: Int, exitReason: Int?): String? {
        // instalou uma versão nova por cima: o Android fecha a versão antiga — é esperado
        if (prevVersionCode != 0 && prevVersionCode != currentVersionCode) return null
        return when (exitReason) {
            null, REASON_UNKNOWN -> "O app fechou com a tela aberta sem erro Java (falha nativa/decodificador ou o sistema encerrou por falta de memória)."
            REASON_PACKAGE_UPDATED, REASON_USER_REQUESTED, REASON_USER_STOPPED, REASON_EXIT_SELF, REASON_PACKAGE_STATE_CHANGE -> null
            REASON_LOW_MEMORY -> "O Android fechou o app por falta de memória no aparelho."
            REASON_CRASH_NATIVE -> "O app fechou por uma falha nativa (decodificador de vídeo/sistema)."
            REASON_ANR -> "O app travou e o Android o fechou (não respondia)."
            REASON_SIGNALED -> "O sistema encerrou o app (provavelmente para liberar memória)."
            REASON_EXCESSIVE_RESOURCE_USAGE -> "O Android fechou o app por uso excessivo de recursos."
            else -> "O app fechou com a tela aberta sem erro Java (código do sistema $exitReason)."
        }
    }

    // valores de android.app.ApplicationExitInfo (constantes estáveis; assim o teste roda sem Android)
    internal const val REASON_UNKNOWN = 0
    internal const val REASON_EXIT_SELF = 1
    internal const val REASON_SIGNALED = 2
    internal const val REASON_LOW_MEMORY = 3
    internal const val REASON_CRASH_NATIVE = 5
    internal const val REASON_ANR = 6
    internal const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    internal const val REASON_USER_REQUESTED = 10
    internal const val REASON_USER_STOPPED = 11
    internal const val REASON_PACKAGE_STATE_CHANGE = 12
    internal const val REASON_PACKAGE_UPDATED = 15

    /** Motivo real da última saída do processo (Android 11+). */
    private fun lastExitReason(context: Context, since: Long): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                ?.takeIf { it.timestamp >= since - 5_000 }?.reason
        }.getOrNull()
    }

    fun install(context: Context, versionName: String) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val dir = app.filesDir
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentCode = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode }
        }.getOrDefault(0)
        // a sessão anterior ficou "na tela" e não há registro novo -> fechou sem exceção Java
        runCatching {
            val p = prefs!!
            if (p.getBoolean("foreground", false)) {
                val step = p.getString("step", "-") ?: "-"
                val at = p.getLong("foregroundAt", 0L)
                val file = File(dir, FILE)
                val reason = unexpectedExitReason(p.getInt("versionCode", 0), currentCode, lastExitReason(app, at))
                if (reason != null && (!file.exists() || file.lastModified() < at)) {
                    file.writeText(header(versionName, "-", System.currentTimeMillis()) + "$reason\n" + "Último passo: $step\n")
                    p.edit().putBoolean("seen", false).commit()
                }
                p.edit().putBoolean("foreground", false).commit()
            }
            // o "último passo" é desta sessão (antes ficava o da sessão anterior, ex.: "prévia tocando")
            p.edit().putInt("versionCode", currentCode).putString("step", "abrindo o app").commit()
            lastStep = "abrindo o app"
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                if (error is OutOfMemoryError) reserve = null // libera memória para conseguir gravar
                val text = if (error is OutOfMemoryError) {
                    "Hugo Player $versionName · OutOfMemoryError (falta de memória)\nthread ${thread.name}\nÚltimo passo: $lastStep\n\n" +
                        error.stackTrace.take(25).joinToString("\n") { "  at $it" }
                } else {
                    format(error, thread.name, versionName) + "\nÚltimo passo: $lastStep\n"
                }
                FileOutputStream(File(dir, FILE)).use { it.write(text.take(MAX_CHARS).toByteArray()) }
                prefs?.edit()?.putBoolean("seen", false)?.putBoolean("foreground", false)?.commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Marca onde o usuário está (barato: só memória + apply). */
    fun step(text: String) {
        lastStep = text
        runCatching { prefs?.edit()?.putString("step", text)?.apply() }
    }

    /** Chamar em onResume (true) / onPause (false). */
    fun setForeground(on: Boolean) {
        runCatching {
            prefs?.edit()?.putBoolean("foreground", on)?.apply { if (on) putLong("foregroundAt", System.currentTimeMillis()) }?.commit()
        }
    }

    private fun header(versionName: String, threadName: String, now: Long) =
        "Hugo Player $versionName · Android ${Build.VERSION.RELEASE ?: "?"} · ${Build.MANUFACTURER ?: "?"} ${Build.MODEL ?: "?"}\n" +
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(now)) + " · thread $threadName\n\n"

    fun format(error: Throwable, threadName: String, versionName: String, now: Long = System.currentTimeMillis()): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return (header(versionName, threadName, now) + sw.toString()).take(MAX_CHARS)
    }

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Registro novo ainda não mostrado no aviso de abertura. */
    fun unseen(context: Context): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getBoolean("seen", true)) null else read(context)
    }

    fun markSeen(context: Context) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("seen", true).apply() }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
        markSeen(context)
    }
}
