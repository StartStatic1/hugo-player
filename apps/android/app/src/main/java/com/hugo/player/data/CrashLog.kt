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

    fun install(context: Context, versionName: String) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val dir = app.filesDir
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // a sessão anterior ficou "na tela" e não há registro novo -> fechou sem exceção Java
        runCatching {
            val p = prefs!!
            if (p.getBoolean("foreground", false)) {
                val step = p.getString("step", "-") ?: "-"
                val at = p.getLong("foregroundAt", 0L)
                val file = File(dir, FILE)
                if (!file.exists() || file.lastModified() < at) {
                    file.writeText(
                        header(versionName, "-", System.currentTimeMillis()) +
                            "O app fechou com a tela aberta sem erro Java (falha nativa/decodificador ou o sistema encerrou por falta de memória).\n" +
                            "Último passo: $step\n"
                    )
                    p.edit().putBoolean("seen", false).commit()
                }
                p.edit().putBoolean("foreground", false).commit()
            }
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
