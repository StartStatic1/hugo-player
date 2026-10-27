package com.hugo.player

import com.hugo.player.data.SupportLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportLinkTest {
    @Test fun cleanOnlyWhatsappHttps() {
        assertEquals(SupportLink.DEFAULT_WHATSAPP, SupportLink.clean("https://chat.whatsapp.com/BRjgrHqSFD008NdzZSMB8P?s=cl&p=a&mlu=4&ilr=4&iam=2"))
        assertEquals("https://wa.me/5511999999999", SupportLink.clean(" https://wa.me/5511999999999 "))
        assertNull(SupportLink.clean("http://chat.whatsapp.com/x"))
        assertNull(SupportLink.clean("https://example.com/x"))
        assertNull(SupportLink.clean(""))
        assertNull(SupportLink.clean(null))
        assertEquals("chat.whatsapp.com/BRjgrHqSFD008NdzZSMB8P", SupportLink.display(SupportLink.DEFAULT_WHATSAPP))
    }
}
