package com.hugo.player

import com.hugo.player.data.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashExitTest {
    @Test fun updateIsNotACrash() {
        // estava na 0.5.41 (46) e abriu a 0.5.42 (47): o Android fechou a versão antiga ao instalar
        assertNull(CrashLog.unexpectedExitReason(46, 47, null))
        assertNull(CrashLog.unexpectedExitReason(47, 47, CrashLog.REASON_PACKAGE_UPDATED))
        assertNull(CrashLog.unexpectedExitReason(47, 47, CrashLog.REASON_USER_REQUESTED))
        assertNull(CrashLog.unexpectedExitReason(47, 47, CrashLog.REASON_USER_STOPPED))
    }

    @Test fun reasonCodesMatchAndroid() {
        // constantes do SDK são inline: o teste confere nossos números contra o Android
        assertEquals(android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED, CrashLog.REASON_PACKAGE_UPDATED)
        assertEquals(android.app.ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, CrashLog.REASON_PACKAGE_STATE_CHANGE)
        assertEquals(android.app.ApplicationExitInfo.REASON_USER_REQUESTED, CrashLog.REASON_USER_REQUESTED)
        assertEquals(android.app.ApplicationExitInfo.REASON_USER_STOPPED, CrashLog.REASON_USER_STOPPED)
        assertEquals(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, CrashLog.REASON_LOW_MEMORY)
        assertEquals(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, CrashLog.REASON_CRASH_NATIVE)
        assertEquals(android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED, CrashLog.REASON_DEPENDENCY_DIED)
        assertEquals(android.app.ApplicationExitInfo.REASON_OTHER, CrashLog.REASON_OTHER)
        assertEquals(android.app.ApplicationExitInfo.REASON_FREEZER, CrashLog.REASON_FREEZER)
        assertEquals(android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE, CrashLog.REASON_PERMISSION_CHANGE)
        assertEquals(android.app.ApplicationExitInfo.REASON_CRASH, CrashLog.REASON_CRASH)
        assertEquals(android.app.ApplicationExitInfo.REASON_ANR, CrashLog.REASON_ANR)
        assertEquals(android.app.ApplicationExitInfo.REASON_SIGNALED, CrashLog.REASON_SIGNALED)
        assertEquals(android.app.ApplicationExitInfo.REASON_EXIT_SELF, CrashLog.REASON_EXIT_SELF)
        assertEquals(android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, CrashLog.REASON_EXCESSIVE_RESOURCE_USAGE)
        assertEquals(android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, CrashLog.REASON_INITIALIZATION_FAILURE)
    }

    @Test fun code16IsTheUpdate() {
        // o aviso "código do sistema 16" da 0.5.43 era a atualização
        assertNull(CrashLog.unexpectedExitReason(0, 48, 16))
        assertNull(CrashLog.unexpectedExitReason(0, 48, 15))
        assertTrue(CrashLog.isStaleUpdateNotice("O app fechou com a tela aberta sem erro Java (código do sistema 16).\nÚltimo passo: x"))
        assertFalse(CrashLog.isStaleUpdateNotice("O app fechou por uma falha nativa (decodificador de vídeo/sistema)."))
        assertFalse(CrashLog.isStaleUpdateNotice(null))
    }

    @Test fun realProblemsAreStillReported() {
        assertTrue(CrashLog.unexpectedExitReason(47, 47, CrashLog.REASON_LOW_MEMORY)!!.contains("memória"))
        assertTrue(CrashLog.unexpectedExitReason(47, 47, CrashLog.REASON_CRASH_NATIVE)!!.contains("nativa"))
        assertNotNull(CrashLog.unexpectedExitReason(47, 47, null))
        // instalação antiga sem versão salva: mantém o aviso de antes
        assertNotNull(CrashLog.unexpectedExitReason(0, 47, null))
    }
}
