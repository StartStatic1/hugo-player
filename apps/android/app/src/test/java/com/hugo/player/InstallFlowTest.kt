package com.hugo.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Atualização: tela do instalador do sistema primeiro (com botão Atualizar), sessão só como reserva e sempre pedindo confirmação. */
class InstallFlowTest {
    @Test
    fun instaladorDoSistemaPrimeiroESessaoComConfirmacao() {
        val src = File("src/main/java/com/hugo/player/data/ApkInstall.kt").readText()
        val body = src.substringAfter("fun install(context: Context, apk: File)").substringBefore("private fun installWithSession")
        val session = body.indexOf("installWithSession(context, apk)")
        assertTrue(body.indexOf("tryStart(context, intent, \"system installer\")") in 0 until session)
        assertTrue("\"Abrir com\" antes da sessão", body.indexOf("buildViewChooser") in 0 until session)
        assertTrue(src.contains("USER_ACTION_REQUIRED"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("Android 11+ precisa enxergar o instalador", manifest.contains("android.intent.action.INSTALL_PACKAGE"))
    }
}
