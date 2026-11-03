package com.hugo.player.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SecureDnsTest {
    @Test fun blockAddressesDetected() {
        assertTrue(SecureDns.looksBlocked(listOf(InetAddress.getByName("0.0.0.0"))))
        assertTrue(SecureDns.looksBlocked(listOf(InetAddress.getByName("127.0.0.1"))))
        assertTrue(SecureDns.looksBlocked(listOf(InetAddress.getByName("10.1.2.3"))))
        assertFalse(SecureDns.looksBlocked(listOf(InetAddress.getByName("104.21.93.241"))))
    }

    /** DoH de verdade (precisa de internet no CI): o host bloqueado pela operadora resolve pelo Cloudflare/Google. */
    @Test fun dohResolvesBlockedHost() {
        SecureDns.mode = "on"
        val got = SecureDns.lookup("cerejadoce.live")
        SecureDns.mode = "auto"
        assertTrue("sem endereço", got.isNotEmpty())
        assertFalse(SecureDns.looksBlocked(got))
    }
}
