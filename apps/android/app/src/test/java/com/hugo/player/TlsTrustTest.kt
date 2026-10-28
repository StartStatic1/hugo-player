package com.hugo.player

import com.hugo.player.data.HugoApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException

class TlsTrustTest {
    private fun fp(c: X509Certificate) =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02X".format(it) }

    @Test
    fun raizesEmbutidasSaoAsOficiaisDoLetsEncrypt() {
        val expected = mapOf(
            "isrg_root_x1" to "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6",
            "isrg_root_x2" to "69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470",
            "isrg_root_ye" to "E14FFCAD5B0025731006CAA43A121A22D8E9700F4FB9CF852F02A708AA5D5666",
        )
        val cf = CertificateFactory.getInstance("X.509")
        expected.forEach { (name, sha) ->
            val c = File("src/main/res/raw/$name.pem").inputStream().use { cf.generateCertificate(it) } as X509Certificate
            assertEquals(name, sha, fp(c))
            assertEquals("$name é raiz autoassinada", c.subjectX500Principal, c.issuerX500Principal)
            c.verify(c.publicKey)
        }
        val nsc = File("src/main/res/xml/network_security_config.xml").readText()
        expected.keys.forEach { assertTrue(nsc.contains("@raw/$it")) }
        assertTrue(nsc.contains("src=\"system\""))
        assertTrue("listas http:// continuam funcionando", nsc.contains("cleartextTrafficPermitted=\"true\""))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("@xml/network_security_config"))
    }

    @Test
    fun erroDeCertificadoViraMensagemClara() {
        val e = SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")
        e.initCause(CertPathValidatorException("Trust anchor for certification path not found."))
        assertTrue(HugoApi.isTlsError(e))
        val msg = HugoApi.friendlyNetworkError(e)
        assertTrue(msg.startsWith("Não foi possível conectar com segurança ao servidor do Hugo Player"))
        assertTrue(!msg.contains("CertPath"))
        assertTrue(HugoApi.friendlyNetworkError(java.net.UnknownHostException("x")).contains("Verifique a internet"))
    }
}
