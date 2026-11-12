package com.hugo.player

import com.hugo.player.vpn.VpnPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class VpnPlaybackTest {
    private class HttpErr(val code: Int) : IOException("HTTP $code")
    private fun status(t: Throwable?) = VpnPlayback.geoBlockStatus(t) { (it as? HttpErr)?.code }

    @Test fun direct403and451() {
        assertEquals(403, status(HttpErr(403)))
        assertEquals(451, status(HttpErr(451)))
    }

    @Test fun nestedCauseChain() {
        val e = RuntimeException("playback", IOException("source", HttpErr(403)))
        assertEquals(403, status(e))
    }

    @Test fun otherCodesAndNoHttpIgnored() {
        assertNull(status(HttpErr(404)))
        assertNull(status(HttpErr(401)))
        assertNull(status(RuntimeException("x", IOException("y"))))
        assertNull(status(null))
    }

    @Test fun firstHttpErrorWins() {
        assertNull(status(RuntimeException(HttpErr(500).apply { initCause(HttpErr(403)) })))
    }
}
