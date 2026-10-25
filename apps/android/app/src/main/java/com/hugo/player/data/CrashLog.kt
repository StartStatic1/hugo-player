package com.hugo.player.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Guarda o último fechamento inesperado num arquivo local para ver em Configurações. */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 12_000
    @Volatile private var installed = false

    fun install(context: Context, versionName: String) {
        if (installed) return
        installed = true
        val dir = context.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(dir, FILE).writeText(format(error, thread.name, versionName)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun format(error: Throwable, threadName: String, versionName: String, now: Long = System.currentTimeMillis()): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val head = "Hugo Player $versionName · Android ${Build.VERSION.RELEASE ?: "?"} · ${Build.MODEL ?: "?"}\n" +
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(now)) + " · thread $threadName\n\n"
        return (head + sw.toString()).take(MAX_CHARS)
    }

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }
}
