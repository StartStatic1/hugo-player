package com.hugo.player

import com.hugo.player.data.CrashLog
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

    @Test fun realProblemsAreStillReported() {
        assertTrue(CrashLog.unexpectedExitReason(47, 47, CrashLog.REASON_LOW_MEMORY)!!.contains("memória"))
        assertTrue(CrashLog.unexpectedExitReason(47, 47, CrashLog.REASON_CRASH_NATIVE)!!.contains("nativa"))
        assertNotNull(CrashLog.unexpectedExitReason(47, 47, null))
        // instalação antiga sem versão salva: mantém o aviso de antes
        assertNotNull(CrashLog.unexpectedExitReason(0, 47, null))
    }
}
